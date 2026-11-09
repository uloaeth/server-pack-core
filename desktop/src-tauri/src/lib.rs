use std::fs;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use tauri::menu::{MenuBuilder, SubmenuBuilder};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{
    AppHandle, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent,
};
use tauri_plugin_notification::NotificationExt;

const DEFAULT_SERVER: &str = "https://c2.spc-cloud.net";
const MAIN: &str = "main";
const SETUP: &str = "setup";

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DesktopConfig {
    server: Option<String>,
}

fn config_path(app: &AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|dir| dir.join("desktop.json"))
        .map_err(|error| format!("config_dir: {error}"))
}

fn read_config(app: &AppHandle) -> DesktopConfig {
    let Ok(path) = config_path(app) else {
        return DesktopConfig { server: None };
    };
    let Ok(raw) = fs::read_to_string(path) else {
        return DesktopConfig { server: None };
    };
    serde_json::from_str(&raw).unwrap_or(DesktopConfig { server: None })
}

fn normalize_server(value: &str) -> Result<String, String> {
    let trimmed = value.trim().trim_end_matches('/');
    let url = Url::parse(trimmed).map_err(|_| "URL no válida".to_string())?;
    let host = url.host_str().ok_or_else(|| "La URL no tiene host".to_string())?;
    let local = host == "localhost" || host == "127.0.0.1" || host == "::1";
    if url.scheme() != "https" && !(url.scheme() == "http" && local) {
        return Err("Usa https:// salvo para localhost".to_string());
    }
    if url.query().is_some() || url.fragment().is_some() {
        return Err("La URL del panel no lleva query ni fragmento".to_string());
    }
    Ok(trimmed.to_string())
}

fn navigate_main(app: &AppHandle, server: &str) -> Result<(), String> {
    let window = app
        .get_webview_window(MAIN)
        .ok_or_else(|| "ventana principal no disponible".to_string())?;
    let url = Url::parse(server).map_err(|error| error.to_string())?;
    window.navigate(url).map_err(|error| error.to_string())?;
    window.show().map_err(|error| error.to_string())?;
    window.set_focus().map_err(|error| error.to_string())
}

fn open_setup_window(app: &AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_webview_window(SETUP) {
        window.show().map_err(|error| error.to_string())?;
        window.set_focus().map_err(|error| error.to_string())?;
        return Ok(());
    }
    WebviewWindowBuilder::new(app, SETUP, WebviewUrl::App("index.html".into()))
        .title("SPC Console · servidor")
        .inner_size(560.0, 430.0)
        .resizable(false)
        .center()
        .build()
        .map_err(|error| error.to_string())?;
    Ok(())
}

#[tauri::command]
fn get_server(app: AppHandle) -> Option<String> {
    read_config(&app)
        .server
        .or_else(|| Some(DEFAULT_SERVER.to_string()))
}

#[tauri::command]
fn set_server(app: AppHandle, server: String) -> Result<String, String> {
    let server = normalize_server(&server)?;
    let path = config_path(&app)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    let raw = serde_json::to_vec_pretty(&DesktopConfig {
        server: Some(server.clone()),
    })
    .map_err(|error| error.to_string())?;
    fs::write(path, raw).map_err(|error| error.to_string())?;

    if let Some(setup) = app.get_webview_window(SETUP) {
        let _ = setup.hide();
    }
    navigate_main(&app, &server)?;
    Ok(server)
}

#[tauri::command]
fn open_server_settings(app: AppHandle) -> Result<(), String> {
    open_setup_window(&app)
}

#[tauri::command]
fn set_title(app: AppHandle, title: String) -> Result<(), String> {
    let window = app
        .get_webview_window(MAIN)
        .ok_or_else(|| "ventana principal no disponible".to_string())?;
    window
        .set_title(&format!("{title} · SPC Console"))
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn set_tray_status(app: AppHandle, online: bool, count: u32) {
    if let Some(tray) = app.tray_by_id("main") {
        let tooltip = if count > 0 {
            format!("SPC Console · {count} online")
        } else if online {
            "SPC Console · conectado".to_string()
        } else {
            "SPC Console · sin conexión".to_string()
        };
        let _ = tray.set_tooltip(Some(tooltip));
    }
}

#[tauri::command]
fn desktop_notify(app: AppHandle, title: String, body: String) -> Result<(), String> {
    app.notification()
        .builder()
        .title(title)
        .body(body)
        .show()
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn open_external(app: AppHandle, url: String) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    app.opener()
        .open_url(url, None::<&str>)
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn server_default() -> &'static str {
    DEFAULT_SERVER
}

#[tauri::command]
fn reload_main(window: WebviewWindow) -> Result<(), String> {
    window.reload().map_err(|error| error.to_string())
}

fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MAIN) {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn quit(app: &AppHandle) {
    app.exit(0);
}

fn build_menu(app: &AppHandle) -> tauri::Result<()> {
    let file = SubmenuBuilder::new(app, "Archivo")
        .text("server", "Cambiar servidor…")
        .separator()
        .text("quit", "Salir")
        .build()?;
    let view = SubmenuBuilder::new(app, "Ver")
        .text("reload", "Recargar")
        .text("shortcuts", "Atajos de teclado")
        .build()?;
    let help = SubmenuBuilder::new(app, "Ayuda")
        .text("about", "Acerca de SPC Console")
        .build()?;
    let menu = MenuBuilder::new(app).items(&[&file, &view, &help]).build()?;
    app.set_menu(menu)?;
    Ok(())
}

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let menu = MenuBuilder::new(app)
        .text("open", "Abrir consola")
        .separator()
        .text("server", "Cambiar servidor…")
        .text("quit", "Salir")
        .build()?;
    let mut builder = TrayIconBuilder::with_id("main")
        .tooltip("SPC Console")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main(app),
            "server" => {
                let _ = open_setup_window(app);
            }
            "quit" => quit(app),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone());
    }
    builder.build(app)?;
    Ok(())
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_store::Builder::default().build())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main(app);
        }))
        .invoke_handler(tauri::generate_handler![
            get_server,
            set_server,
            open_server_settings,
            server_default,
            set_title,
            set_tray_status,
            desktop_notify,
            open_external,
            reload_main
        ])
        .on_menu_event(|app, event| match event.id.as_ref() {
            "server" => {
                let _ = open_setup_window(app);
            }
            "reload" => {
                if let Some(window) = app.get_webview_window(MAIN) {
                    let _ = window.reload();
                }
            }
            "shortcuts" => {
                show_main(app);
                if let Some(window) = app.get_webview_window(MAIN) {
                    let _ = window
                        .eval("window.dispatchEvent(new CustomEvent('spc:shortcuts'))");
                }
            }
            "about" => {
                let _ = app.notification();
                show_main(app);
                if let Some(window) = app.get_webview_window(MAIN) {
                    let _ = window.eval(
                        "window.dispatchEvent(new CustomEvent('spc:about'))",
                    );
                }
            }
            "quit" => quit(app),
            _ => {}
        })
        .setup(|app| {
            let handle = app.handle().clone();
            let server = read_config(&handle).server;
            let window = WebviewWindowBuilder::new(app, MAIN, WebviewUrl::App("index.html".into()))
                .title("SPC Console")
                .inner_size(1480.0, 940.0)
                .min_inner_size(1100.0, 680.0)
                .center()
                .visible(false)
                .build()?;
            build_menu(&handle)?;
            build_tray(&handle)?;

            match server {
                Some(value) => match navigate_main(&handle, &value) {
                    Ok(()) => {}
                    Err(_) => {
                        let _ = open_setup_window(&handle);
                    }
                },
                None => {
                    let _ = window.show();
                    let _ = open_setup_window(&handle);
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() == MAIN {
                if let WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running SPC Console");
}
