const { app, BrowserWindow, session, ipcMain, desktopCapturer, systemPreferences } = require('electron');
const path = require('path');

// Tắt Hardware Acceleration nếu gặp lỗi màn hình đen trên một số máy yếu
// app.disableHardwareAcceleration();

function createWindow() {
    const win = new BrowserWindow({
        width: 1280,
        height: 800,
        minWidth: 800,
        minHeight: 600,
        icon: path.join(__dirname, 'icon.png'), // Đảm bảo bạn có file icon.png hoặc xóa dòng này
        backgroundColor: '#111827', // Màu nền tối khớp với giao diện
        webPreferences: {
            nodeIntegration: true, // Cho phép dùng require trong HTML (quan trọng)
            contextIsolation: false, // Cho phép truy cập biến toàn cục
            enableRemoteModule: true,
            backgroundThrottling: false // Giúp video không bị lag khi minimize cửa sổ
        }
    });

    // Tự động cấp quyền truy cập Camera/Microphone mà không hiện popup hỏi lại
    session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => {
        const allowedPermissions = ['media', 'display-capture', 'audio-capture', 'mediaKeySystem'];
        if (allowedPermissions.includes(permission)) {
            callback(true);
        } else {
            callback(false);
        }
    });

    // Yêu cầu quyền hệ thống (đặc biệt quan trọng trên macOS)
    if (process.platform === 'darwin') {
        systemPreferences.askForMediaAccess('microphone');
        systemPreferences.askForMediaAccess('camera');
    }

    win.loadFile('login.html');
    // win.webContents.openDevTools(); // Bỏ comment dòng này để debug nếu cần
}

// --- XỬ LÝ LẤY DANH SÁCH CỬA SỔ (IPC) ---
// Đây là hàm backend trả về danh sách cửa sổ cho share.html
ipcMain.handle('GET_SOURCES', async (event) => {
    try {
        const sources = await desktopCapturer.getSources({
            types: ['window', 'screen'],
            thumbnailSize: { width: 400, height: 400 }, // Tăng chất lượng thumbnail
            fetchWindowIcons: true
        });
        return sources;
    } catch (e) {
        console.error("Lỗi lấy nguồn phát:", e);
        return [];
    }
});

app.whenReady().then(createWindow);

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
});