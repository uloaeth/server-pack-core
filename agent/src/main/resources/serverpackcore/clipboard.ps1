param([Parameter(Mandatory = $true)][string]$Ops)

$mutex = New-Object -TypeName System.Threading.Mutex -ArgumentList $false, 'Local\FabricSyncClip'
$acquired = $false
try { $acquired = $mutex.WaitOne(0) } catch { $acquired = $true }
if (-not $acquired) { exit 0 }

Add-Type @'
using System;
using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class SpcClip {
    const int WM_CLIPBOARDUPDATE = 0x031D;
    const int WM_APP = 0x8001;
    const int WM_QUIT = 0x0012;
    const int CF_UNICODETEXT = 13;
    const uint GMEM_MOVEABLE = 0x0002;

    public delegate IntPtr WndProc(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);
    [StructLayout(LayoutKind.Sequential)] public struct MSG {
        public IntPtr hwnd; public uint message; public IntPtr wParam; public IntPtr lParam;
        public uint time; public int x; public int y;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] public struct WNDCLASSEX {
        public uint size; public uint style; public IntPtr wndProc; public int clsExtra; public int wndExtra;
        public IntPtr instance; public IntPtr icon; public IntPtr cursor; public IntPtr background;
        public string menu; public string className; public IntPtr iconSmall;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern ushort RegisterClassEx(ref WNDCLASSEX windowClass);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateWindowEx(
        uint exStyle, string className, string title, uint style, int x, int y, int width, int height,
        IntPtr parent, IntPtr menu, IntPtr instance, IntPtr param);
    [DllImport("user32.dll")] static extern bool DestroyWindow(IntPtr window);
    [DllImport("user32.dll")] static extern IntPtr DefWindowProc(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] static extern int GetMessage(out MSG message, IntPtr window, uint min, uint max);
    [DllImport("user32.dll")] static extern bool TranslateMessage(ref MSG message);
    [DllImport("user32.dll")] static extern IntPtr DispatchMessage(ref MSG message);
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] static extern bool AddClipboardFormatListener(IntPtr window);
    [DllImport("user32.dll")] static extern bool RemoveClipboardFormatListener(IntPtr window);
    [DllImport("user32.dll")] static extern bool OpenClipboard(IntPtr window);
    [DllImport("user32.dll")] static extern bool CloseClipboard();
    [DllImport("user32.dll")] static extern bool EmptyClipboard();
    [DllImport("user32.dll")] static extern IntPtr GetClipboardData(uint format);
    [DllImport("user32.dll")] static extern IntPtr SetClipboardData(uint format, IntPtr memory);
    [DllImport("user32.dll")] static extern bool IsClipboardFormatAvailable(uint format);
    [DllImport("kernel32.dll")] static extern IntPtr GlobalAlloc(uint flags, UIntPtr bytes);
    [DllImport("kernel32.dll")] static extern IntPtr GlobalLock(IntPtr memory);
    [DllImport("kernel32.dll")] static extern bool GlobalUnlock(IntPtr memory);
    [DllImport("kernel32.dll")] static extern IntPtr GlobalFree(IntPtr memory);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr GetModuleHandle(string name);

    static WndProc handler;
    static IntPtr window;
    static string lastSet = "";
    static readonly ConcurrentQueue<string> pending = new ConcurrentQueue<string>();

    static string Read() {
        if (!IsClipboardFormatAvailable(CF_UNICODETEXT)) { return null; }
        string text = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            if (!OpenClipboard(window)) { Thread.Sleep(10); continue; }
            try {
                IntPtr handle = GetClipboardData(CF_UNICODETEXT);
                if (handle != IntPtr.Zero) {
                    IntPtr pointer = GlobalLock(handle);
                    if (pointer != IntPtr.Zero) {
                        text = Marshal.PtrToStringUni(pointer);
                        GlobalUnlock(handle);
                    }
                }
            } finally { CloseClipboard(); }
            break;
        }
        return text;
    }

    static void Write(string text) {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (!OpenClipboard(window)) { Thread.Sleep(10); continue; }
            try {
                EmptyClipboard();
                byte[] bytes = Encoding.Unicode.GetBytes(text + "\0");
                IntPtr memory = GlobalAlloc(GMEM_MOVEABLE, (UIntPtr)bytes.Length);
                if (memory == IntPtr.Zero) { return; }
                IntPtr pointer = GlobalLock(memory);
                Marshal.Copy(bytes, 0, pointer, bytes.Length);
                GlobalUnlock(memory);
                if (SetClipboardData(CF_UNICODETEXT, memory) == IntPtr.Zero) {
                    GlobalFree(memory);
                }
            } finally { CloseClipboard(); }
            return;
        }
    }

    static IntPtr Handler(IntPtr source, uint message, IntPtr wParam, IntPtr lParam) {
        try {
            if (message == WM_CLIPBOARDUPDATE) {
                string text = Read();
                if (!String.IsNullOrEmpty(text) && text != lastSet) {
                    string encoded = Convert.ToBase64String(Encoding.UTF8.GetBytes(text));
                    Console.Out.WriteLine("COPY " + encoded);
                    Console.Out.Flush();
                }
            } else if (message == WM_APP) {
                string value;
                if (pending.TryDequeue(out value)) {
                    lastSet = value;
                    Write(value);
                }
            }
        } catch { }
        return DefWindowProc(source, message, wParam, lParam);
    }

    static void Reader() {
        string line;
        while ((line = Console.In.ReadLine()) != null) {
            if (line.StartsWith("SET ")) {
                try {
                    string text = Encoding.UTF8.GetString(Convert.FromBase64String(line.Substring(4)));
                    pending.Enqueue(text);
                    PostMessage(window, WM_APP, IntPtr.Zero, IntPtr.Zero);
                } catch { }
            }
        }
        PostMessage(window, WM_QUIT, IntPtr.Zero, IntPtr.Zero);
    }

    public static void Run() {
        handler = Handler;
        WNDCLASSEX definition = new WNDCLASSEX();
        definition.size = (uint)Marshal.SizeOf(typeof(WNDCLASSEX));
        definition.wndProc = Marshal.GetFunctionPointerForDelegate(handler);
        definition.className = "SpcClipWnd";
        definition.instance = GetModuleHandle(null);
        RegisterClassEx(ref definition);
        window = CreateWindowEx(0, "SpcClipWnd", "SpcClip", 0, 0, 0, 0, 0,
            new IntPtr(-3), IntPtr.Zero, definition.instance, IntPtr.Zero);
        if (window == IntPtr.Zero) { return; }
        AddClipboardFormatListener(window);
        Thread reader = new Thread(Reader);
        reader.IsBackground = true;
        reader.Start();
        MSG message;
        while (GetMessage(out message, IntPtr.Zero, 0, 0) != 0) {
            TranslateMessage(ref message);
            DispatchMessage(ref message);
        }
        RemoveClipboardFormatListener(window);
        DestroyWindow(window);
    }
}
'@

try {
    [SpcClip]::Run()
} finally {
    $mutex.ReleaseMutex()
}
