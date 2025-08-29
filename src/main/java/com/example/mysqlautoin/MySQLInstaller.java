package com.example.mysqlautoin;

import java.io.*;
import java.nio.file.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MySQLInstaller {

    public static void install(String zipPath, String installDir, String rootPassword, String port,
                               boolean configureEnv, Consumer<String> logConsumer) {
        try {
            if (!WindowsServiceChecker.isAdmin()) {
                logConsumer.accept("⚠️ 当前非管理员，服务注册和 PATH 修改将无法执行\n");
            }

            Path installPath = Paths.get(installDir);
            if (!Files.exists(installPath)) Files.createDirectories(installPath);
            logConsumer.accept("📂 创建安装目录: " + installDir + "\n");

            unzip(zipPath, installDir, logConsumer);

            // 找到解压后的 MySQL 根目录
            Path mysqlRootDir = Files.list(installPath)
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().toLowerCase().contains("mysql"))
                    .findFirst().orElse(null);

            if (mysqlRootDir == null) {
                logConsumer.accept("❌ 未找到解压后的 MySQL 根目录，安装失败\n");
                return;
            }

            Path binPath = mysqlRootDir.resolve("bin");
            if (!Files.exists(binPath)) {
                logConsumer.accept("❌ 未找到 bin 目录，安装失败\n");
                return;
            }

            // 创建 data 目录在 MySQL 根目录
            Path dataDir = mysqlRootDir.resolve("data");
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
            logConsumer.accept("📂 创建数据目录: " + dataDir + "\n");

            // 生成 my.ini 在 MySQL 根目录
            Path myIni = mysqlRootDir.resolve("my.ini");
            try (BufferedWriter writer = Files.newBufferedWriter(myIni)) {
                writer.write("[mysqld]\n");
                writer.write("basedir=" + mysqlRootDir.toAbsolutePath().toString().replace("\\", "\\\\") + "\n");
                writer.write("datadir=" + dataDir.toAbsolutePath().toString().replace("\\", "\\\\") + "\n");
                writer.write("port=" + port + "\n");
                writer.write("character-set-server=utf8mb4\n");
                writer.write("sql-mode=STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION\n");
                writer.write("[client]\n");
                writer.write("port=" + port + "\n");
            }
            logConsumer.accept("📄 my.ini 配置文件已生成: " + myIni + "\n");

            // 初始化数据库
            logConsumer.accept("⚙️ 初始化数据库...\n");
            ProcessBuilder initPb = new ProcessBuilder(
                    binPath.resolve("mysqld.exe").toString(),
                    "--defaults-file=" + myIni.toAbsolutePath(),
                    "--initialize-insecure",
                    "--console"
            );
            initPb.directory(mysqlRootDir.toFile());
            initPb.redirectErrorStream(true);
            Process initProcess = initPb.start();

            // 读取初始化输出
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(initProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logConsumer.accept(line + "\n");
                }
            }

            int initExitCode = initProcess.waitFor();
            if (initExitCode != 0) {
                logConsumer.accept("❌ 数据库初始化失败，退出码: " + initExitCode + "\n");
                return;
            }
            logConsumer.accept("✅ 数据库初始化完成\n");

            // 注册服务 - 使用MySQL自带的安装方式
            if (WindowsServiceChecker.isAdmin()) {
                String serviceName = "MySQL" + port;
                logConsumer.accept("⚙️ 注册服务: " + serviceName + "\n");

                // 首先尝试删除可能存在的旧服务
                try {
                    Process deleteProcess = new ProcessBuilder(
                            binPath.resolve("mysqld.exe").toString(),
                            "--remove",
                            serviceName
                    ).start();
                    deleteProcess.waitFor();
                    logConsumer.accept("ℹ️ 已尝试删除旧服务（如果存在）\n");
                    Thread.sleep(2000); // 等待服务完全删除
                } catch (Exception e) {
                    logConsumer.accept("ℹ️ 删除旧服务时出错（可能服务不存在）: " + e.getMessage() + "\n");
                }

                // 使用MySQL自带的服务安装功能
                ProcessBuilder installPb = new ProcessBuilder(
                        binPath.resolve("mysqld.exe").toString(),
                        "--install",
                        serviceName,
                        "--defaults-file=" + myIni.toAbsolutePath()
                );
                installPb.redirectErrorStream(true);
                Process installProcess = installPb.start();

                // 读取安装输出
                StringBuilder installOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(installProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        installOutput.append(line).append("\n");
                    }
                }

                int installExitCode = installProcess.waitFor();
                if (installExitCode == 0) {
                    logConsumer.accept("✅ 服务注册完成\n");

                    // 配置服务为自动启动
                    Process configProcess = new ProcessBuilder(
                            "cmd.exe", "/c",
                            "sc config " + serviceName + " start= auto"
                    ).start();
                    configProcess.waitFor();

                    // 配置服务账户
                    Process accountProcess = new ProcessBuilder(
                            "cmd.exe", "/c",
                            "sc config " + serviceName + " obj= \"NT AUTHORITY\\LocalService\" password= \"\""
                    ).start();
                    accountProcess.waitFor();

                    // 启动服务
                    Process startProcess = new ProcessBuilder(
                            "cmd.exe", "/c",
                            "net start " + serviceName
                    ).redirectErrorStream(true).start();

                    // 读取启动输出
                    StringBuilder startOutput = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(startProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            startOutput.append(line).append("\n");
                        }
                    }

                    int startExitCode = startProcess.waitFor();
                    if (startExitCode == 0) {
                        logConsumer.accept("▶️ 服务已启动\n");
                    } else {
                        logConsumer.accept("❌ 服务启动失败，返回码：" + startExitCode + "\n");
                        logConsumer.accept("服务启动输出: " + startOutput.toString() + "\n");

                        // 检查MySQL是否仍在运行
                        if (isMySQLRunning(port)) {
                            logConsumer.accept("⚠️ 服务启动报告失败，但MySQL进程似乎在运行\n");
                            logConsumer.accept("这可能是因为服务启动超时或权限问题，但MySQL已成功启动\n");
                        }
                    }
                } else {
                    logConsumer.accept("❌ 服务注册失败，退出码: " + installExitCode + "\n");
                    logConsumer.accept("安装输出: " + installOutput.toString() + "\n");

                    // 回退到sc create方法
                    logConsumer.accept("尝试使用sc create方法注册服务...\n");
                    String scCommand = String.format(
                            "sc create %s binPath= \"\\\"%s\\\" --defaults-file=\\\"%s\\\"\" type= own start= auto displayname= \"MySQL Server %s\"",
                            serviceName,
                            binPath.resolve("mysqld.exe").toAbsolutePath(),
                            myIni.toAbsolutePath(),
                            port
                    );

                    Process scProcess = new ProcessBuilder("cmd.exe", "/c", scCommand).start();
                    int scExitCode = scProcess.waitFor();

                    if (scExitCode == 0) {
                        logConsumer.accept("✅ 使用sc create注册服务成功\n");

                        // 配置服务账户
                        Process accountProcess = new ProcessBuilder(
                                "cmd.exe", "/c",
                                "sc config " + serviceName + " obj= \"NT AUTHORITY\\LocalService\" password= \"\""
                        ).start();
                        accountProcess.waitFor();

                        // 启动服务
                        Process startProcess = new ProcessBuilder(
                                "cmd.exe", "/c",
                                "net start " + serviceName
                        ).redirectErrorStream(true).start();

                        // 读取启动输出
                        StringBuilder startOutput = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(startProcess.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                startOutput.append(line).append("\n");
                            }
                        }

                        int startExitCode = startProcess.waitFor();
                        if (startExitCode == 0) {
                            logConsumer.accept("▶️ 服务已启动\n");
                        } else {
                            logConsumer.accept("❌ 服务启动失败，返回码：" + startExitCode + "\n");
                            logConsumer.accept("服务启动输出: " + startOutput.toString() + "\n");

                            // 检查MySQL是否仍在运行
                            if (isMySQLRunning(port)) {
                                logConsumer.accept("⚠️ 服务启动报告失败，但MySQL进程似乎在运行\n");
                                logConsumer.accept("这可能是因为服务启动超时或权限问题，但MySQL已成功启动\n");
                            }
                        }
                    } else {
                        logConsumer.accept("❌ 使用sc create注册服务失败，退出码: " + scExitCode + "\n");
                        logConsumer.accept("💡 可能需要手动注册服务或重启系统\n");
                    }
                }
            } else {
                logConsumer.accept("⚠️ 非管理员模式，跳过服务注册\n");
            }

            // 设置root密码
            setRootPassword(binPath, rootPassword, port, logConsumer);

            // 配置环境变量
            if (configureEnv && WindowsServiceChecker.isAdmin()) {
                String pathToAdd = binPath.toAbsolutePath().toString();
                Process envProcess = new ProcessBuilder("cmd.exe", "/c",
                        "for /f \"skip=2 tokens=1,2*\" %a in ('reg query \"HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment\" /v Path') do setx PATH \"%c;" + pathToAdd + "\" /M").start();
                envProcess.waitFor();
                logConsumer.accept("📌 已将 bin 加入 PATH: " + pathToAdd + "\n");
            } else if (configureEnv) {
                logConsumer.accept("⚠️ 非管理员模式，无法修改 PATH，请手动添加 bin 目录\n");
            }

            logConsumer.accept("🎉 MySQL 安装完成！\n");
            logConsumer.accept("📋 连接信息:\n");
            logConsumer.accept("   主机: localhost\n");
            logConsumer.accept("   端口: " + port + "\n");
            logConsumer.accept("   用户: root\n");
            logConsumer.accept("   密码: " + rootPassword + "\n");

        } catch (Exception e) {
            logConsumer.accept("❌ 安装失败: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
    }

    private static boolean isMySQLRunning(String port) {
        try {
            // 检查端口是否被监听
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("localhost", Integer.parseInt(port)), 1000);
            socket.close();
            return true;
        } catch (Exception e) {
            // 检查mysqld进程是否存在
            try {
                Process process = Runtime.getRuntime().exec("tasklist /fi \"imagename eq mysqld.exe\"");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("mysqld.exe")) {
                        return true;
                    }
                }
            } catch (Exception ex) {
                // 忽略异常
            }
            return false;
        }
    }

    private static void setRootPassword(Path binPath, String password, String port, Consumer<String> logConsumer) {
        try {
            logConsumer.accept("⚙️ 设置 root 密码...\n");

            // 等待MySQL服务完全启动
            Thread.sleep(5000);

            ProcessBuilder pb = new ProcessBuilder(
                    binPath.resolve("mysql.exe").toString(),
                    "-u", "root",
                    "--protocol=tcp",
                    "--port=" + port,
                    "--execute", "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + password + "'; FLUSH PRIVILEGES;"
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (Boolean.parseBoolean((line = String.valueOf(reader.readLine() != null)))) {
                    logConsumer.accept(line + "\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logConsumer.accept("✅ Root 密码设置成功\n");
            } else {
                logConsumer.accept("⚠️ 设置 root 密码失败，退出码: " + exitCode + "\n");
                logConsumer.accept("💡 提示: 请手动执行以下命令设置密码:\n");
                logConsumer.accept("   " + binPath.resolve("mysql.exe").toString() + " -u root --protocol=tcp --port=" + port + " -e \"ALTER USER 'root'@'localhost' IDENTIFIED BY '" + password + "';\"\n");
            }
        } catch (Exception e) {
            logConsumer.accept("⚠️ 设置 root 密码时出错: " + e.getMessage() + "\n");
        }
    }

    private static void unzip(String zipFilePath, String destDir, Consumer<String> logConsumer) throws IOException {
        byte[] buffer = new byte[4096];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path filePath = Paths.get(destDir, entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(filePath);
                } else {
                    // 创建父目录
                    Files.createDirectories(filePath.getParent());

                    try (FileOutputStream fos = new FileOutputStream(filePath.toFile());
                         BufferedOutputStream bos = new BufferedOutputStream(fos, buffer.length)) {

                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        logConsumer.accept("📦 ZIP 解压完成: " + zipFilePath + "\n");
    }
}