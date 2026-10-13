#include <initguid.h>
#include <windows.h>
#include <dshow.h>
#include <olectl.h>

#pragma comment(lib, "strmiids.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")

// CamLinks VirtualCam 专属设备 GUID: {B6E8C3B1-9481-4C57-9F93-79358BE2C140}
static const GUID CLSID_CamLinksVirtualCam =
{ 0xb6e8c3b1, 0x9481, 0x4c57, { 0x9f, 0x93, 0x79, 0x35, 0x8b, 0xe2, 0xc1, 0x40 } };

static const wchar_t* g_DeviceFriendlyName = L"CamLinks VirtualCam";
static const wchar_t* g_SharedMemoryName = L"CamLinks_CaptureInstance0";

static volatile LONG g_ServerLocks = 0;
static HINSTANCE g_hInstance = nullptr;

#pragma pack(push, 1)
struct SharedImageMemoryHeader {
    int width;
    int height;
    int format;     // 0: ARGB32
    int resizeMode;
    int mirrorMode;
    int timeout;
};
#pragma pack(pop)

class CamLinksVirtualFilter;

// 动态视频格式构造工具
static void BuildDynamicVideoMediaType(AM_MEDIA_TYPE* pmt, int width, int height) {
    ZeroMemory(pmt, sizeof(AM_MEDIA_TYPE));
    pmt->majortype = MEDIATYPE_Video;
    pmt->subtype = MEDIASUBTYPE_RGB32;
    pmt->bFixedSizeSamples = TRUE;
    pmt->bTemporalCompression = FALSE;
    pmt->lSampleSize = width * height * 4;
    pmt->formattype = FORMAT_VideoInfo;

    VIDEOINFOHEADER* vih = (VIDEOINFOHEADER*)CoTaskMemAlloc(sizeof(VIDEOINFOHEADER));
    ZeroMemory(vih, sizeof(VIDEOINFOHEADER));
    vih->AvgTimePerFrame = 333333; // 30 FPS
    vih->bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    vih->bmiHeader.biWidth = width;
    vih->bmiHeader.biHeight = height;
    vih->bmiHeader.biPlanes = 1;
    vih->bmiHeader.biBitCount = 32;

    vih->bmiHeader.biCompression = BI_RGB;
    vih->bmiHeader.biSizeImage = width * height * 4;

    pmt->cbFormat = sizeof(VIDEOINFOHEADER);
    pmt->pbFormat = (BYTE*)vih;
}

static void GetSharedMemoryResolution(int& outWidth, int& outHeight) {
    outWidth = 1080;
    outHeight = 1920; // 默认竖屏比例
    HANDLE hMem = OpenFileMappingW(FILE_MAP_READ, FALSE, g_SharedMemoryName);
    if (hMem) {
        void* pView = MapViewOfFile(hMem, FILE_MAP_READ, 0, 0, sizeof(SharedImageMemoryHeader));
        if (pView) {
            SharedImageMemoryHeader* pHeader = (SharedImageMemoryHeader*)pView;
            if (pHeader->width > 0 && pHeader->height > 0) {
                outWidth = pHeader->width;
                outHeight = pHeader->height;
            }
            UnmapViewOfFile(pView);
        }
        CloseHandle(hMem);
    }
}

// 媒体格式枚举器
class CamLinksEnumMediaTypes : public IEnumMediaTypes {
private:
    volatile LONG m_refCount;
    ULONG m_index;

public:
    CamLinksEnumMediaTypes() : m_refCount(1), m_index(0) {}

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IEnumMediaTypes) {
            *ppv = static_cast<IEnumMediaTypes*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }

    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&m_refCount); }
    STDMETHODIMP_(ULONG) Release() override {
        LONG c = InterlockedDecrement(&m_refCount);
        if (c == 0) delete this;
        return c;
    }

    STDMETHODIMP Next(ULONG cMediaTypes, AM_MEDIA_TYPE** ppMediaTypes, ULONG* pcFetched) override {
        if (!ppMediaTypes) return E_POINTER;
        if (m_index == 0 && cMediaTypes > 0) {
            int w, h;
            GetSharedMemoryResolution(w, h);
            ppMediaTypes[0] = (AM_MEDIA_TYPE*)CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE));
            BuildDynamicVideoMediaType(ppMediaTypes[0], w, h);
            m_index++;
            if (pcFetched) *pcFetched = 1;
            return S_OK;
        }
        if (pcFetched) *pcFetched = 0;
        return S_FALSE;
    }

    STDMETHODIMP Skip(ULONG cMediaTypes) override {
        m_index += cMediaTypes;
        return (m_index <= 1) ? S_OK : S_FALSE;
    }

    STDMETHODIMP Reset() override {
        m_index = 0;
        return S_OK;
    }

    STDMETHODIMP Clone(IEnumMediaTypes** ppEnum) override {
        if (!ppEnum) return E_POINTER;
        *ppEnum = new CamLinksEnumMediaTypes();
        return S_OK;
    }
};

// 满足 OBS 硬件探针并执行实时推流的视频捕获引脚
class CamLinksPin : public IPin, public IAMStreamConfig, public IKsPropertySet {
private:
    volatile LONG m_refCount;
    CamLinksVirtualFilter* m_pFilter;
    IPin* m_pConnectedPin;
    IMemInputPin* m_pInputPin;
    IMemAllocator* m_pAllocator;
    HANDLE m_hThread;
    HANDLE m_hStopEvent;
    HANDLE m_hSharedMemory;
    void* m_pMemoryView;

    static DWORD WINAPI StreamingThreadProc(LPVOID lpParam) {
        CamLinksPin* pPin = (CamLinksPin*)lpParam;
        pPin->StreamingLoop();
        return 0;
    }

    void StreamingLoop() {
        REFERENCE_TIME frameIndex = 0;
        int activeWidth = 0;
        int activeHeight = 0;

        while (WaitForSingleObject(m_hStopEvent, 33) == WAIT_TIMEOUT) {
            if (!m_pAllocator || !m_pInputPin) continue;

            IMediaSample* pSample = nullptr;
            if (SUCCEEDED(m_pAllocator->GetBuffer(&pSample, nullptr, nullptr, 0))) {
                BYTE* pDst = nullptr;
                if (SUCCEEDED(pSample->GetPointer(&pDst))) {
                    if (!m_pMemoryView) {
                        if (!m_hSharedMemory) {
                            m_hSharedMemory = OpenFileMappingW(FILE_MAP_READ, FALSE, g_SharedMemoryName);
                        }
                        if (m_hSharedMemory) {
                            m_pMemoryView = MapViewOfFile(m_hSharedMemory, FILE_MAP_READ, 0, 0, 0);
                        }
                    }

                    if (m_pMemoryView) {
                        SharedImageMemoryHeader* pHeader = (SharedImageMemoryHeader*)m_pMemoryView;
                        int curW = pHeader->width;
                        int curH = pHeader->height;

                        if (curW > 0 && curH > 0) {
                            // 动态通知 OBS 切换分辨率与长宽比 (Dynamic Format Change)
                            if (curW != activeWidth || curH != activeHeight) {
                                activeWidth = curW;
                                activeHeight = curH;
                                AM_MEDIA_TYPE newMt;
                                BuildDynamicVideoMediaType(&newMt, curW, curH);
                                pSample->SetMediaType(&newMt);
                                CoTaskMemFree(newMt.pbFormat);
                            }

                            // 1:1 动态原生输出，长宽比完全跟随手机，绝不拉伸变形
                            DWORD* pSrcPixels = (DWORD*)((BYTE*)m_pMemoryView + sizeof(SharedImageMemoryHeader));
                            DWORD* pDstPixels = (DWORD*)pDst;
                            for (int y = 0; y < curH; y++) {
                                int srcY = curH - 1 - y; // 消除 DIB 倒序
                                CopyMemory(pDstPixels + y * curW, pSrcPixels + srcY * curW, curW * 4);
                            }

                            pSample->SetActualDataLength(curW * curH * 4);
                        }
                    }
                }

                REFERENCE_TIME rtStart = frameIndex * 333333;
                REFERENCE_TIME rtEnd = rtStart + 333333;
                pSample->SetTime(&rtStart, &rtEnd);
                pSample->SetSyncPoint(TRUE);
                frameIndex++;

                m_pInputPin->Receive(pSample);
                pSample->Release();
            }
        }
    }

public:
    CamLinksPin(CamLinksVirtualFilter* pFilter) 
        : m_refCount(1), m_pFilter(pFilter), m_pConnectedPin(nullptr),
          m_pInputPin(nullptr), m_pAllocator(nullptr), m_hThread(nullptr),
          m_hStopEvent(nullptr), m_hSharedMemory(nullptr), m_pMemoryView(nullptr) {}

    virtual ~CamLinksPin() {
        StopStreaming();
        if (m_pMemoryView) {
            UnmapViewOfFile(m_pMemoryView);
            m_pMemoryView = nullptr;
        }
        if (m_hSharedMemory) {
            CloseHandle(m_hSharedMemory);
            m_hSharedMemory = nullptr;
        }
    }

    void StartStreaming() {
        StopStreaming();
        if (m_pAllocator) {
            m_pAllocator->Commit();
        }
        m_hStopEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        m_hThread = CreateThread(nullptr, 0, StreamingThreadProc, this, 0, nullptr);
    }

    void StopStreaming() {
        if (m_hStopEvent) {
            SetEvent(m_hStopEvent);
            if (m_hThread) {
                WaitForSingleObject(m_hThread, 1000);
                CloseHandle(m_hThread);
                m_hThread = nullptr;
            }
            CloseHandle(m_hStopEvent);
            m_hStopEvent = nullptr;
        }
        if (m_pAllocator) {
            m_pAllocator->Decommit();
        }
    }

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IPin) {
            *ppv = static_cast<IPin*>(this);
        } else if (riid == IID_IAMStreamConfig) {
            *ppv = static_cast<IAMStreamConfig*>(this);
        } else if (riid == IID_IKsPropertySet) {
            *ppv = static_cast<IKsPropertySet*>(this);
        } else {
            *ppv = nullptr;
            return E_NOINTERFACE;
        }
        AddRef();
        return S_OK;
    }

    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&m_refCount); }
    STDMETHODIMP_(ULONG) Release() override {
        LONG c = InterlockedDecrement(&m_refCount);
        if (c == 0) delete this;
        return c;
    }

    // IPin 核心接口
    STDMETHODIMP Connect(IPin* pReceivePin, const AM_MEDIA_TYPE* pmt) override {
        if (!pReceivePin) return E_POINTER;
        if (m_pConnectedPin) return VFW_E_ALREADY_CONNECTED;

        AM_MEDIA_TYPE mt;
        if (pmt) {
            mt = *pmt;
        } else {
            int w, h;
            GetSharedMemoryResolution(w, h);
            BuildDynamicVideoMediaType(&mt, w, h);
        }

        // 核心握手：通知接收方连接准备就绪
        HRESULT hr = pReceivePin->ReceiveConnection(this, &mt);
        if (FAILED(hr)) {
            return hr;
        }

        m_pConnectedPin = pReceivePin;
        m_pConnectedPin->AddRef();

        if (SUCCEEDED(pReceivePin->QueryInterface(IID_IMemInputPin, (void**)&m_pInputPin))) {
            // 若下游不提供 Allocator，主动自建 Allocator
            if (FAILED(m_pInputPin->GetAllocator(&m_pAllocator)) || !m_pAllocator) {
                CoCreateInstance(CLSID_MemoryAllocator, nullptr, CLSCTX_INPROC_SERVER, IID_IMemAllocator, (void**)&m_pAllocator);
            }

            if (m_pAllocator) {
                ALLOCATOR_PROPERTIES prop = {}, actual = {};
                prop.cBuffers = 2;
                prop.cbBuffer = 3840 * 2160 * 4; // 动态支持 4K 以内任意分辨率切换
                prop.cbAlign = 1;
                m_pAllocator->SetProperties(&prop, &actual);
                m_pInputPin->NotifyAllocator(m_pAllocator, FALSE);
            }
        }
        return S_OK;
    }

    STDMETHODIMP ReceiveConnection(IPin* pConnector, const AM_MEDIA_TYPE* pmt) override { return E_NOTIMPL; }

    STDMETHODIMP Disconnect() override {
        StopStreaming();
        if (m_pAllocator) {
            m_pAllocator->Release();
            m_pAllocator = nullptr;
        }
        if (m_pInputPin) {
            m_pInputPin->Release();
            m_pInputPin = nullptr;
        }
        if (m_pConnectedPin) {
            m_pConnectedPin->Release();
            m_pConnectedPin = nullptr;
        }
        return S_OK;
    }
    STDMETHODIMP ConnectedTo(IPin** pPin) override {
        if (!pPin) return E_POINTER;
        *pPin = m_pConnectedPin;
        if (m_pConnectedPin) m_pConnectedPin->AddRef();
        return m_pConnectedPin ? S_OK : VFW_E_NOT_CONNECTED;
    }
    STDMETHODIMP ConnectionMediaType(AM_MEDIA_TYPE* pmt) override {
        if (!pmt) return E_POINTER;
        int w, h;
        GetSharedMemoryResolution(w, h);
        BuildDynamicVideoMediaType(pmt, w, h);
        return S_OK;
    }
    STDMETHODIMP QueryPinInfo(PIN_INFO* pInfo) override {
        if (!pInfo) return E_POINTER;
        pInfo->pFilter = (IBaseFilter*)m_pFilter;
        if (pInfo->pFilter) pInfo->pFilter->AddRef();
        pInfo->dir = PINDIR_OUTPUT;
        wcsncpy_s(pInfo->achName, L"Capture", 128);
        return S_OK;
    }
    STDMETHODIMP QueryDirection(PIN_DIRECTION* pPinDir) override {
        if (!pPinDir) return E_POINTER;
        *pPinDir = PINDIR_OUTPUT;
        return S_OK;
    }
    STDMETHODIMP QueryId(LPWSTR* Id) override {
        if (!Id) return E_POINTER;
        *Id = (LPWSTR)CoTaskMemAlloc(16);
        wcscpy_s(*Id, 8, L"Capture");
        return S_OK;
    }
    STDMETHODIMP QueryAccept(const AM_MEDIA_TYPE* pmt) override { return S_OK; }
    STDMETHODIMP EnumMediaTypes(IEnumMediaTypes** ppEnum) override {
        if (!ppEnum) return E_POINTER;
        *ppEnum = new CamLinksEnumMediaTypes();
        return S_OK;
    }
    STDMETHODIMP QueryInternalConnections(IPin** apPin, ULONG* nPin) override { return E_NOTIMPL; }
    STDMETHODIMP EndOfStream() override { return S_OK; }
    STDMETHODIMP BeginFlush() override { return S_OK; }
    STDMETHODIMP EndFlush() override { return S_OK; }
    STDMETHODIMP NewSegment(REFERENCE_TIME tStart, REFERENCE_TIME tStop, double dRate) override { return S_OK; }

    // IAMStreamConfig 流参数配置
    STDMETHODIMP SetFormat(AM_MEDIA_TYPE* pmt) override { return S_OK; }
    STDMETHODIMP GetFormat(AM_MEDIA_TYPE** ppmt) override {
        if (!ppmt) return E_POINTER;
        int w, h;
        GetSharedMemoryResolution(w, h);
        *ppmt = (AM_MEDIA_TYPE*)CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE));
        BuildDynamicVideoMediaType(*ppmt, w, h);
        return S_OK;
    }
    STDMETHODIMP GetNumberOfCapabilities(int* piCount, int* piSize) override {
        if (!piCount || !piSize) return E_POINTER;
        *piCount = 1;
        *piSize = sizeof(VIDEO_STREAM_CONFIG_CAPS);
        return S_OK;
    }
    STDMETHODIMP GetStreamCaps(int iIndex, AM_MEDIA_TYPE** ppmt, BYTE* pSCC) override {
        if (!ppmt || !pSCC) return E_POINTER;
        int w, h;
        GetSharedMemoryResolution(w, h);
        *ppmt = (AM_MEDIA_TYPE*)CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE));
        BuildDynamicVideoMediaType(*ppmt, w, h);
        VIDEO_STREAM_CONFIG_CAPS* caps = (VIDEO_STREAM_CONFIG_CAPS*)pSCC;
        ZeroMemory(caps, sizeof(VIDEO_STREAM_CONFIG_CAPS));
        caps->guid = FORMAT_VideoInfo;
        caps->VideoStandard = 0;
        caps->InputSize.cx = 1920;
        caps->InputSize.cy = 1080;
        caps->MinCroppingSize.cx = 1920;
        caps->MinCroppingSize.cy = 1080;
        caps->MaxCroppingSize.cx = 1920;
        caps->MaxCroppingSize.cy = 1080;
        caps->MinOutputSize.cx = 1920;
        caps->MinOutputSize.cy = 1080;
        caps->MaxOutputSize.cx = 1920;
        caps->MaxOutputSize.cy = 1080;
        caps->MinFrameInterval = 333333;
        caps->MaxFrameInterval = 333333;
        return S_OK;
    }

    // IKsPropertySet（OBS 确认摄像头引脚的核心依据：PIN_CATEGORY_CAPTURE）
    STDMETHODIMP Set(REFGUID guidPropSet, DWORD dwPropID, LPVOID pInstanceData, DWORD cbInstanceData, LPVOID pPropData, DWORD cbPropData) override { return E_NOTIMPL; }
    STDMETHODIMP Get(REFGUID guidPropSet, DWORD dwPropID, LPVOID pInstanceData, DWORD cbInstanceData, LPVOID pPropData, DWORD cbPropData, DWORD* pcbReturned) override {
        if (guidPropSet == AMPROPSETID_Pin && dwPropID == 0) { // AMPROPERTY_PIN_CATEGORY
            if (pPropData && cbPropData >= sizeof(GUID)) {
                CopyMemory(pPropData, &PIN_CATEGORY_CAPTURE, sizeof(GUID));
                if (pcbReturned) *pcbReturned = sizeof(GUID);
                return S_OK;
            }
        }
        return E_PROP_ID_UNSUPPORTED;
    }
    STDMETHODIMP QuerySupported(REFGUID guidPropSet, DWORD dwPropID, DWORD* pTypeSupport) override {
        if (guidPropSet == AMPROPSETID_Pin && dwPropID == 0) {
            if (pTypeSupport) *pTypeSupport = KSPROPERTY_SUPPORT_GET;
            return S_OK;
        }
        return E_PROP_SET_UNSUPPORTED;
    }
};

// 引脚枚举器
class CamLinksEnumPins : public IEnumPins {
private:
    volatile LONG m_refCount;
    CamLinksPin* m_pPin;
    ULONG m_index;

public:
    CamLinksEnumPins(CamLinksPin* pPin) : m_refCount(1), m_pPin(pPin), m_index(0) {
        if (m_pPin) m_pPin->AddRef();
    }
    virtual ~CamLinksEnumPins() {
        if (m_pPin) m_pPin->Release();
    }

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IEnumPins) {
            *ppv = static_cast<IEnumPins*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&m_refCount); }
    STDMETHODIMP_(ULONG) Release() override {
        LONG c = InterlockedDecrement(&m_refCount);
        if (c == 0) delete this;
        return c;
    }
    STDMETHODIMP Next(ULONG cPins, IPin** ppPins, ULONG* pcFetched) override {
        if (!ppPins) return E_POINTER;
        if (m_index == 0 && cPins > 0 && m_pPin) {
            ppPins[0] = m_pPin;
            m_pPin->AddRef();
            m_index++;
            if (pcFetched) *pcFetched = 1;
            return S_OK;
        }
        if (pcFetched) *pcFetched = 0;
        return S_FALSE;
    }
    STDMETHODIMP Skip(ULONG cPins) override {
        m_index += cPins;
        return (m_index <= 1) ? S_OK : S_FALSE;
    }
    STDMETHODIMP Reset() override {
        m_index = 0;
        return S_OK;
    }
    STDMETHODIMP Clone(IEnumPins** ppEnum) override {
        if (!ppEnum) return E_POINTER;
        *ppEnum = new CamLinksEnumPins(m_pPin);
        return S_OK;
    }
};

class CamLinksVirtualFilter : public IBaseFilter {
private:
    volatile LONG m_refCount;
    FILTER_STATE m_state;
    IFilterGraph* m_pGraph;
    CamLinksPin* m_pPin;

public:
    CamLinksVirtualFilter() : m_refCount(1), m_state(State_Stopped), m_pGraph(nullptr) {
        InterlockedIncrement(&g_ServerLocks);
        m_pPin = new CamLinksPin(this);
    }

    virtual ~CamLinksVirtualFilter() {
        if (m_pPin) {
            m_pPin->Release();
            m_pPin = nullptr;
        }
        InterlockedDecrement(&g_ServerLocks);
    }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IPersist || riid == IID_IMediaFilter || riid == IID_IBaseFilter) {
            *ppv = static_cast<IBaseFilter*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }

    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&m_refCount); }
    STDMETHODIMP_(ULONG) Release() override {
        LONG count = InterlockedDecrement(&m_refCount);
        if (count == 0) delete this;
        return count;
    }

    // IPersist
    STDMETHODIMP GetClassID(CLSID* pClassID) override {
        if (!pClassID) return E_POINTER;
        *pClassID = CLSID_CamLinksVirtualCam;
        return S_OK;
    }

    // IMediaFilter
    STDMETHODIMP Stop() override {
        m_state = State_Stopped;
        if (m_pPin) m_pPin->StopStreaming();
        return S_OK;
    }

    STDMETHODIMP Pause() override {
        m_state = State_Paused;
        return S_OK;
    }

    STDMETHODIMP Run(REFERENCE_TIME tStart) override {
        m_state = State_Running;
        if (m_pPin) m_pPin->StartStreaming();
        return S_OK;
    }

    STDMETHODIMP GetState(DWORD dwMilliSecsTimeout, FILTER_STATE* State) override {
        if (!State) return E_POINTER;
        *State = m_state;
        return S_OK;
    }

    STDMETHODIMP SetSyncSource(IReferenceClock* pClock) override { return S_OK; }
    STDMETHODIMP GetSyncSource(IReferenceClock** pClock) override {
        if (!pClock) return E_POINTER;
        *pClock = nullptr;
        return S_OK;
    }

    // IBaseFilter (返回包含有效引脚的枚举器)
    STDMETHODIMP EnumPins(IEnumPins** ppEnum) override {
        if (!ppEnum) return E_POINTER;
        *ppEnum = new CamLinksEnumPins(m_pPin);
        return S_OK;
    }

    STDMETHODIMP FindPin(LPCWSTR Id, IPin** ppPin) override {
        if (!ppPin) return E_POINTER;
        if (m_pPin) {
            *ppPin = m_pPin;
            m_pPin->AddRef();
            return S_OK;
        }
        *ppPin = nullptr;
        return VFW_E_NOT_FOUND;
    }

    STDMETHODIMP QueryFilterInfo(FILTER_INFO* pInfo) override {
        if (!pInfo) return E_POINTER;
        wcsncpy_s(pInfo->achName, g_DeviceFriendlyName, 128);
        pInfo->pGraph = m_pGraph;
        if (m_pGraph) m_pGraph->AddRef();
        return S_OK;
    }

    STDMETHODIMP JoinFilterGraph(IFilterGraph* pGraph, LPCWSTR pName) override {
        m_pGraph = pGraph;
        return S_OK;
    }

    STDMETHODIMP QueryVendorInfo(LPWSTR* pVendorInfo) override { return E_NOTIMPL; }
};

class CamLinksClassFactory : public IClassFactory {
private:
    volatile LONG m_refCount;

public:
    CamLinksClassFactory() : m_refCount(1) {}

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }

    STDMETHODIMP_(ULONG) AddRef() override {
        return InterlockedIncrement(&m_refCount);
    }

    STDMETHODIMP_(ULONG) Release() override {
        LONG count = InterlockedDecrement(&m_refCount);
        if (count == 0) {
            delete this;
        }
        return count;
    }

    STDMETHODIMP CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) override {
        if (pUnkOuter) return CLASS_E_NOAGGREGATION;
        CamLinksVirtualFilter* pFilter = new CamLinksVirtualFilter();
        HRESULT hr = pFilter->QueryInterface(riid, ppv);
        pFilter->Release();
        return hr;
    }

    STDMETHODIMP LockServer(BOOL fLock) override {
        if (fLock) {
            InterlockedIncrement(&g_ServerLocks);
        }
        else {
            InterlockedDecrement(&g_ServerLocks);
        }
        return S_OK;
    }
};

BOOL APIENTRY DllMain(HMODULE hModule, DWORD ul_reason_for_call, LPVOID lpReserved) {
    if (ul_reason_for_call == DLL_PROCESS_ATTACH) {
        g_hInstance = (HINSTANCE)hModule;
        DisableThreadLibraryCalls(hModule);
    }
    return TRUE;
}

STDAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, void** ppv) {
    if (rclsid != CLSID_CamLinksVirtualCam) return CLASS_E_CLASSNOTAVAILABLE;
    CamLinksClassFactory* pFactory = new CamLinksClassFactory();
    HRESULT hr = pFactory->QueryInterface(riid, ppv);
    pFactory->Release();
    return hr;
}

STDAPI DllCanUnloadNow() {
    return (g_ServerLocks == 0) ? S_OK : S_FALSE;
}

static const wchar_t* g_ClsidString = L"{B6E8C3B1-9481-4C57-9F93-79358BE2C140}";
static const wchar_t* g_VideoInputCategory = L"{860BB310-5D01-11d0-BD3B-00A0C911CE86}";

STDAPI DllRegisterServer() {
    wchar_t modulePath[MAX_PATH];
    GetModuleFileNameW(g_hInstance, modulePath, MAX_PATH);

    wchar_t subKey[256];
    HKEY hKey;

    // 1. 注册 COM InprocServer32
    wsprintfW(subKey, L"CLSID\\%s", g_ClsidString);
    if (RegCreateKeyExW(HKEY_CLASSES_ROOT, subKey, 0, nullptr, 0, KEY_WRITE, nullptr, &hKey, nullptr) == ERROR_SUCCESS) {
        RegSetValueExW(hKey, nullptr, 0, REG_SZ, (const BYTE*)g_DeviceFriendlyName, (DWORD)(wcslen(g_DeviceFriendlyName) + 1) * sizeof(wchar_t));
        RegCloseKey(hKey);
    }

    wsprintfW(subKey, L"CLSID\\%s\\InprocServer32", g_ClsidString);
    if (RegCreateKeyExW(HKEY_CLASSES_ROOT, subKey, 0, nullptr, 0, KEY_WRITE, nullptr, &hKey, nullptr) == ERROR_SUCCESS) {
        RegSetValueExW(hKey, nullptr, 0, REG_SZ, (const BYTE*)modulePath, (DWORD)(wcslen(modulePath) + 1) * sizeof(wchar_t));
        const wchar_t* threading = L"Both";
        RegSetValueExW(hKey, L"ThreadingModel", 0, REG_SZ, (const BYTE*)threading, (DWORD)(wcslen(threading) + 1) * sizeof(wchar_t));
        RegCloseKey(hKey);
    }

    // 2. 通过 IFilterMapper2 为 DirectShow 自动生成合法的 FilterData 设备引脚元数据
    CoInitialize(nullptr);
    IFilterMapper2* pFM = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_FilterMapper2, nullptr, CLSCTX_INPROC_SERVER, IID_IFilterMapper2, (void**)&pFM);
    if (SUCCEEDED(hr) && pFM) {
        REGPINTYPES pinType = {};
        pinType.clsMajorType = &MEDIATYPE_Video;
        pinType.clsMinorType = &MEDIASUBTYPE_RGB32;

        REGFILTERPINS pin = {};
        pin.strName = (LPWSTR)L"Output";
        pin.bRendered = FALSE;
        pin.bOutput = TRUE;
        pin.bZero = FALSE;
        pin.bMany = FALSE;
        pin.nMediaTypes = 1;
        pin.lpMediaType = &pinType;

        REGFILTER2 rf = {};
        rf.dwVersion = 1;
        rf.dwMerit = MERIT_DO_NOT_USE;
        rf.cPins = 1;
        rf.rgPins = &pin;

        pFM->RegisterFilter(CLSID_CamLinksVirtualCam, g_DeviceFriendlyName, nullptr, &CLSID_VideoInputDeviceCategory, nullptr, &rf);
        pFM->Release();
    }
    CoUninitialize();

    return S_OK;
}

STDAPI DllUnregisterServer() {
    CoInitialize(nullptr);
    IFilterMapper2* pFM = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_FilterMapper2, nullptr, CLSCTX_INPROC_SERVER, IID_IFilterMapper2, (void**)&pFM))) {
        pFM->UnregisterFilter(&CLSID_VideoInputDeviceCategory, nullptr, CLSID_CamLinksVirtualCam);
        pFM->Release();
    }
    CoUninitialize();

    wchar_t subKey[256];
    wsprintfW(subKey, L"CLSID\\%s\\InprocServer32", g_ClsidString);
    RegDeleteKeyW(HKEY_CLASSES_ROOT, subKey);

    wsprintfW(subKey, L"CLSID\\%s", g_ClsidString);
    RegDeleteKeyW(HKEY_CLASSES_ROOT, subKey);

    return S_OK;
}