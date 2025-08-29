package com.example.mysqlautoin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class WindowsServiceChecker {

    public static class ServiceInfo {
        private final String name;
        private final String state;
        private final String binPath;
        private final String displayName;

        public ServiceInfo(String name, String state, String binPath, String displayName) {
            this.name = name;
            this.state = state;
            this.binPath = binPath;
            this.displayName = displayName;
        }

        public String getName() { return name; }
        public String getState() { return state; }
        public String getBinPath() { return binPath; }
        public String getDisplayName() { return displayName; }

        @Override
        public String toString() {
            return (displayName != null ? displayName : name) + " (" + state + ")";
        }
    }

    public static List<ServiceInfo> checkMysqlServices() {
        List<ServiceInfo> result = new ArrayList<>();
        try {
            // 方法1: 使用SC命令查找所有状态的服务
            result = findServicesWithSC();

            // 方法2: 如果SC命令没有找到服务，尝试使用WMIC命令
            if (result.isEmpty()) {
                result = findServicesWithWMIC();
            }

            // 方法3: 如果仍然没有找到，尝试直接查找MySQL进程
            if (result.isEmpty()) {
                result = findServicesByProcess();
            }

        } catch (Exception e) {
            System.err.println("检查MySQL服务时出错: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    // 使用SC命令查找服务
    private static List<ServiceInfo> findServicesWithSC() {
        List<ServiceInfo> result = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("cmd.exe", "/c", "sc query type= service state= all").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"));

            String line;
            String currentService = null;
            String state = null;
            String displayName = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("SERVICE_NAME:")) {
                    // 保存上一个服务的处理结果
                    if (currentService != null && state != null) {
                        if (isValidMysqlService(currentService, displayName, null)) {
                            String binPath = getServiceBinPath(currentService);
                            result.add(new ServiceInfo(currentService, state, binPath, displayName));
                        }
                    }

                    // 开始处理新服务
                    currentService = line.substring("SERVICE_NAME:".length()).trim();
                    state = null;
                    displayName = null;
                }
                else if (currentService != null && line.startsWith("STATE")) {
                    if (line.contains("RUNNING")) state = "正在运行";
                    else if (line.contains("STOPPED")) state = "已停止";
                    else if (line.contains("START_PENDING")) state = "启动中";
                    else if (line.contains("STOP_PENDING")) state = "停止中";
                    else if (line.contains("PAUSED")) state = "已暂停";
                    else state = "其他状态";
                }
                else if (currentService != null && line.startsWith("DISPLAY_NAME")) {
                    displayName = line.substring("DISPLAY_NAME:".length()).trim();
                }
            }

            // 处理最后一个服务
            if (currentService != null && state != null) {
                if (isValidMysqlService(currentService, displayName, null)) {
                    String binPath = getServiceBinPath(currentService);
                    result.add(new ServiceInfo(currentService, state, binPath, displayName));
                }
            }

        } catch (Exception e) {
            System.err.println("使用SC命令查找服务时出错: " + e.getMessage());
        }
        return result;
    }

    // 使用WMIC查找MySQL服务
    private static List<ServiceInfo> findServicesWithWMIC() {
        List<ServiceInfo> result = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("cmd.exe", "/c",
                    "wmic service where \"name like '%mysql%' or displayname like '%mysql%' or pathname like '%mysql%'\" get name, displayname, state, pathname /format:csv").start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"));
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (firstLine) {
                    firstLine = false;
                    continue; // 跳过标题行
                }

                String[] parts = line.split(",");
                if (parts.length >= 4) {
                    String name = parts[0].trim();
                    String displayName = parts[1].trim();
                    String state = parts[2].trim().equalsIgnoreCase("Running") ? "正在运行" : "已停止";
                    String pathname = parts[3].trim();

                    // 进一步验证这是否真的是MySQL服务
                    if (isValidMysqlService(name, displayName, pathname)) {
                        String binPath = extractBinPath(pathname);
                        result.add(new ServiceInfo(name, state, binPath, displayName));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("使用WMIC查找服务时出错: " + e.getMessage());
        }
        return result;
    }

    // 通过进程查找MySQL服务
    private static List<ServiceInfo> findServicesByProcess() {
        List<ServiceInfo> result = new ArrayList<>();
        try {
            // 查找mysqld进程
            Process process = new ProcessBuilder("cmd.exe", "/c", "tasklist /fi \"imagename eq mysqld.exe\" /fo csv /nh").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"));

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.contains("mysqld.exe")) continue;

                // 找到mysqld进程，创建一个虚拟的服务信息
                result.add(new ServiceInfo("MySQL (进程)", "正在运行", null, "MySQL (从进程检测)"));
                break;
            }
        } catch (Exception e) {
            System.err.println("通过进程查找服务时出错: " + e.getMessage());
        }
        return result;
    }

    // 验证是否为有效的MySQL服务
    private static boolean isValidMysqlService(String serviceName, String displayName, String pathname) {
        if (serviceName == null) return false;

        String lowerServiceName = serviceName.toLowerCase();
        String lowerDisplayName = (displayName != null) ? displayName.toLowerCase() : "";
        String lowerPathname = (pathname != null) ? pathname.toLowerCase() : "";

        // 排除明显不是MySQL服务的名称
        if (lowerServiceName.equals("mysqlrouter") ||
                lowerServiceName.equals("mysqlnotifier") ||
                lowerServiceName.equals("mysqlinstaller") ||
                lowerServiceName.equals("mysqlworkbench")) {
            return false;
        }

        // 检查服务名称、显示名称或路径是否包含MySQL相关关键词
        boolean isMysql = lowerServiceName.contains("mysql") ||
                lowerDisplayName.contains("mysql") ||
                lowerPathname.contains("mysql") ||
                lowerServiceName.contains("mysqld") ||
                lowerDisplayName.contains("mysqld") ||
                lowerPathname.contains("mysqld");

        // 排除计算机名等误判
        if (isMysql) {
            // 检查服务名称是否看起来像计算机名（不包含mysql但被误判）
            if (lowerServiceName.length() > 12 && !lowerServiceName.contains("mysql") &&
                    !lowerDisplayName.contains("mysql") && !lowerPathname.contains("mysql")) {
                return false;
            }

            // 检查服务名称是否包含常见计算机名前缀
            if (lowerServiceName.startsWith("desktop-") ||
                    lowerServiceName.startsWith("win-") ||
                    lowerServiceName.startsWith("pc-")) {
                return false;
            }
        }

        return isMysql;
    }

    // 获取服务的二进制路径
    private static String getServiceBinPath(String serviceName) {
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", "sc qc \"" + serviceName + "\"").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "GBK"));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("BINARY_PATH_NAME")) {
                    String path = line.split(":", 2)[1].trim();
                    return extractBinPath(path);
                }
            }
        } catch (Exception e) {
            System.err.println("获取服务详情时出错: " + e.getMessage());
        }
        return null;
    }

    // 从完整路径中提取可执行文件路径
    private static String extractBinPath(String pathname) {
        if (pathname == null || pathname.isEmpty()) {
            return null;
        }

        // 提取可执行文件路径
        String binPath = pathname;

        // 去除引号
        if (binPath.startsWith("\"") && binPath.endsWith("\"")) {
            binPath = binPath.substring(1, binPath.length() - 1);
        }

        // 提取到mysqld.exe或mysql.exe的路径
        if (binPath.toLowerCase().contains("mysqld.exe")) {
            int index = binPath.toLowerCase().indexOf("mysqld.exe");
            binPath = binPath.substring(0, index + "mysqld.exe".length());
        } else if (binPath.toLowerCase().contains("mysql.exe")) {
            int index = binPath.toLowerCase().indexOf("mysql.exe");
            binPath = binPath.substring(0, index + "mysql.exe".length());
        }

        // 获取父目录（bin目录）
        File exeFile = new File(binPath);
        if (exeFile.exists()) {
            return exeFile.getParent();
        }

        // 如果文件不存在，尝试从路径中提取bin目录
        if (binPath.toLowerCase().contains("\\bin\\")) {
            int binIndex = binPath.toLowerCase().indexOf("\\bin\\");
            return binPath.substring(0, binIndex + 4); // 4是"\bin"的长度
        }

        return null;
    }

    // 启动或停止服务
    public static boolean startStopService(String serviceName, boolean start, StringBuilder log) {
        try {
            String command = start ? "net start \"" + serviceName + "\"" : "net stop \"" + serviceName + "\"";
            Process process = new ProcessBuilder("cmd.exe", "/c", command).start();

            // 读取命令输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.append("✅ ").append(start ? "启动" : "停止").append("服务成功: ").append(serviceName).append("\n");
                return true;
            } else {
                log.append("❌ ").append(start ? "启动" : "停止").append("服务失败: ").append(serviceName).append("\n");
                log.append("错误输出: ").append(output.toString()).append("\n");

                // 如果是启动失败，尝试使用SC命令获取更多信息
                if (start) {
                    Process scProcess = new ProcessBuilder("cmd.exe", "/c", "sc query \"" + serviceName + "\"").start();
                    StringBuilder scOutput = new StringBuilder();
                    try (BufferedReader scReader = new BufferedReader(new InputStreamReader(scProcess.getInputStream(), "GBK"))) {
                        String line;
                        while ((line = scReader.readLine()) != null) {
                            scOutput.append(line).append("\n");
                        }
                    }
                    log.append("服务状态详情: \n").append(scOutput.toString()).append("\n");
                }

                return false;
            }
        } catch (Exception e) {
            log.append("❌ ").append(start ? "启动" : "停止").append("服务时发生错误: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    // 尝试自动启动MySQL服务
    public static boolean tryAutoStartMysqlService(String serviceName, StringBuilder log) {
        try {
            // 检查服务是否存在
            Process checkProcess = new ProcessBuilder("cmd.exe", "/c", "sc query \"" + serviceName + "\"").start();
            int exitCode = checkProcess.waitFor();

            if (exitCode != 0) {
                log.append("❌ 服务不存在: ").append(serviceName).append("\n");
                return false;
            }

            // 尝试启动服务
            Process startProcess = new ProcessBuilder("cmd.exe", "/c", "net start \"" + serviceName + "\"").start();
            int startExitCode = startProcess.waitFor();

            if (startExitCode == 0) {
                log.append("✅ 服务启动成功: ").append(serviceName).append("\n");
                return true;
            } else {
                // 读取错误输出
                StringBuilder errorOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(startProcess.getInputStream(), "GBK"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                    }
                }
                log.append("❌ 服务启动失败: ").append(serviceName).append("\n");
                log.append("错误信息: ").append(errorOutput.toString()).append("\n");
                return false;
            }
        } catch (Exception e) {
            log.append("❌ 尝试启动服务时发生错误: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    public static boolean uninstallService(ServiceInfo service, StringBuilder log) {
        if (!isAdmin()) {
            log.append("❌ 当前非管理员，无法卸载服务\n");
            return false;
        }

        try {
            // 先尝试使用MySQL自带的卸载方式
            if (service.getBinPath() != null) {
                File mysqldExe = new File(service.getBinPath(), "mysqld.exe");
                if (mysqldExe.exists()) {
                    Process removeProcess = new ProcessBuilder(
                            mysqldExe.getAbsolutePath(),
                            "--remove",
                            service.getName()
                    ).start();

                    int removeExitCode = removeProcess.waitFor();
                    if (removeExitCode == 0) {
                        log.append("✅ 使用MySQL自带工具卸载服务成功: ").append(service.getName()).append("\n");
                        return true;
                    }
                }
            }

            // 如果MySQL自带方式失败，回退到sc delete
            if (service.getState().equals("正在运行")) {
                log.append("⚠️ 服务正在运行，尝试停止...\n");
                if (!startStopService(service.getName(), false, log)) {
                    log.append("❌ 停止服务失败，无法卸载\n");
                    return false;
                }
            }

            // 删除服务
            Process deleteProcess = new ProcessBuilder("cmd.exe", "/c", "sc delete \"" + service.getName() + "\"").start();
            int deleteExitCode = deleteProcess.waitFor();

            if (deleteExitCode == 0) {
                log.append("✅ 服务删除成功: ").append(service.getName()).append("\n");
                return true;
            } else {
                log.append("❌ 删除服务失败: ").append(service.getName()).append("\n");
                return false;
            }
        } catch (Exception e) {
            log.append("❌ 卸载异常: ").append(e.getMessage()).append("\n");
            e.printStackTrace();
            return false;
        }
    }

    public static void deleteInstallDir(ServiceInfo service, StringBuilder log) {
        String binPath = service.getBinPath();
        if (binPath == null) {
            log.append("⚠️ 未找到服务对应安装目录\n");
            return;
        }

        File binDir = new File(binPath);
        File installDir = binDir.getParentFile();

        try {
            if (!installDir.exists()) {
                log.append("⚠️ 目录不存在: ").append(installDir.getAbsolutePath()).append("\n");
                return;
            }

            // 检查是否包含MySQL文件，避免误删
            if (!isLikelyMysqlDir(installDir)) {
                log.append("⚠️ 目录可能不是MySQL安装目录，跳过删除: ").append(installDir.getAbsolutePath()).append("\n");
                return;
            }

            // 先尝试正常删除
            boolean success = deleteDirectory(installDir, log);

            if (!success) {
                log.append("⚠️ 正常删除失败，尝试强制删除...\n");

                // 使用命令行强制删除
                Process process = new ProcessBuilder("cmd.exe", "/c",
                        "rd /s /q \"" + installDir.getAbsolutePath() + "\"").start();
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    log.append("✅ 强制删除成功: ").append(installDir.getAbsolutePath()).append("\n");
                } else {
                    log.append("❌ 强制删除也失败: ").append(installDir.getAbsolutePath()).append("\n");
                }
            }

            // 从PATH中移除
            if (isAdmin()) {
                removeFromPath(binPath, log);
            }
        } catch (Exception e) {
            log.append("❌ 删除目录异常: ").append(e.getMessage()).append("\n");
            e.printStackTrace();
        }
    }

    private static boolean isLikelyMysqlDir(File dir) {
        // 检查目录是否包含典型的MySQL文件和目录
        File binDir = new File(dir, "bin");
        File dataDir = new File(dir, "data");
        File myIni = new File(dir, "my.ini");
        File myCnf = new File(dir, "my.cnf");

        return (binDir.exists() && binDir.isDirectory()) ||
                (dataDir.exists() && dataDir.isDirectory()) ||
                myIni.exists() || myCnf.exists();
    }

    private static boolean deleteDirectory(File dir, StringBuilder log) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    boolean success = deleteDirectory(child, log);
                    if (!success) {
                        return false;
                    }
                }
            }
        }

        boolean deleted = dir.delete();
        if (deleted) {
            log.append("🗑 删除: ").append(dir.getAbsolutePath()).append("\n");
        } else {
            log.append("❌ 删除失败: ").append(dir.getAbsolutePath()).append("\n");
        }
        return deleted;
    }

    private static void removeFromPath(String pathToRemove, StringBuilder log) {
        try {
            // 获取系统PATH
            Process getPath = new ProcessBuilder("cmd.exe", "/c", "echo %PATH%").start();
            String path = new BufferedReader(new InputStreamReader(getPath.getInputStream(), "GBK")).readLine();

            if (path == null || path.isEmpty()) {
                log.append("⚠️ 无法获取PATH环境变量\n");
                return;
            }

            // 分割PATH并移除指定路径
            String[] paths = path.split(";");
            StringBuilder newPath = new StringBuilder();
            boolean found = false;

            for (String p : paths) {
                if (!p.equalsIgnoreCase(pathToRemove) && !p.trim().isEmpty()) {
                    if (newPath.length() > 0) {
                        newPath.append(";");
                    }
                    newPath.append(p);
                } else if (p.equalsIgnoreCase(pathToRemove)) {
                    found = true;
                }
            }

            if (found) {
                // 更新PATH
                Process setPath = new ProcessBuilder("cmd.exe", "/c",
                        "setx PATH \"" + newPath.toString() + "\" /M").start();
                int exitCode = setPath.waitFor();

                if (exitCode == 0) {
                    log.append("✅ 已从 PATH 移除: ").append(pathToRemove).append("\n");
                } else {
                    log.append("❌ 更新PATH失败\n");
                }
            } else {
                log.append("ℹ️ 路径不在PATH中: ").append(pathToRemove).append("\n");
            }
        } catch (Exception e) {
            log.append("❌ 从PATH移除路径时出错: ").append(e.getMessage()).append("\n");
        }
    }

    public static boolean isAdmin() {
        try {
            // 使用更可靠的管理员权限检查方法
            Process p = new ProcessBuilder("cmd.exe", "/c", "net session >nul 2>&1").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}