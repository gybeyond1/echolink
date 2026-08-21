fn main() {
    slint_build::compile("src/ui.slint").expect("slint 编译失败");
    tauri_build::build()
}
