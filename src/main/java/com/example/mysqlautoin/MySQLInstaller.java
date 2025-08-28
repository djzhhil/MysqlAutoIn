package com.example.mysqlautoin;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Windows 下 MySQL 安装工具类
 */
public class MySQLInstaller {

    public static void install(String zipFilePath, String installPath, String rootPassword, String port, Consumer<String> logCallback) {
        new Thread(() -> {
            try {
                log("⏳ 开始安装 MySQL...", logCallback);

                // 1. 创建安装目录
                File dir = new File(installPath);
                if (!dir.exists()) dir.mkdirs();
                log("✔ 安装目录准备完成: " + installPath, logCallback);

                // 2. 解压 ZIP
                log("📦 解压 MySQL ZIP 包...", logCallback);
                Process unzip = new ProcessBuilder("powershell",
                        "Expand-Archive",
                        "-Path", zipFilePath,
                        "-DestinationPath", installPath,
                        "-Force").start();
                unzip.waitFor();

                // 3. 找到解压后的 MySQL 根目录
                String finalInstallPath;
                File[] dirs = new File(installPath).listFiles(File::isDirectory);
                if (dirs != null && dirs.length == 1) {
                    finalInstallPath = dirs[0].getAbsolutePath();
                } else {
                    // 如果有多个目录，尝试找到包含 bin 的那个
                    File found = null;
                    if (dirs != null) {
                        for (File f : dirs) {
                            if (new File(f, "bin").exists()) {
                                found = f;
                                break;
                            }
                        }
                    }
                    finalInstallPath = (found != null) ? found.getAbsolutePath() : installPath;
                }
                log("✔ MySQL 根目录: " + finalInstallPath, logCallback);

                // 4. 初始化数据库
                log("⚙ 初始化数据库...", logCallback);
                Process init = new ProcessBuilder(
                        finalInstallPath + "\\bin\\mysqld.exe",
                        "--initialize-insecure",
                        "--basedir=" + finalInstallPath,
                        "--datadir=" + finalInstallPath + "\\data"
                ).start();
                init.waitFor();

                // 5. 注册 Windows 服务
                log("🛠 注册 Windows 服务...", logCallback);
                Process installService = new ProcessBuilder(
                        finalInstallPath + "\\bin\\mysqld.exe",
                        "--install", "MySQL"
                ).start();
                installService.waitFor();

                // 6. 启动服务
                log("▶ 启动 MySQL 服务...", logCallback);
                Process startService = new ProcessBuilder("net", "start", "MySQL").start();
                startService.waitFor();

                // 7. 设置 root 密码
                log("🔑 设置 root 密码...", logCallback);
                Process setPwd = new ProcessBuilder(
                        finalInstallPath + "\\bin\\mysqladmin.exe",
                        "-u", "root",
                        "password", rootPassword
                ).start();
                setPwd.waitFor();

                log("🎉 MySQL 安装完成！", logCallback);

            } catch (IOException | InterruptedException e) {
                log("❌ 安装失败: " + e.getMessage(), logCallback);
            }
        }).start();
    }

    private static void log(String msg, Consumer<String> logCallback) {
        if (logCallback != null) {
            logCallback.accept(msg + "\n");
        }
        System.out.println(msg);
    }
}
