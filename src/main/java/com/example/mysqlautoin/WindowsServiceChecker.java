package com.example.mysqlautoin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows 服务检查工具类
 */
public class WindowsServiceChecker {

    /**
     * 服务信息
     */
    public static class ServiceInfo {
        private final String name;
        private final String state;

        public ServiceInfo(String name, String state) {
            this.name = name;
            this.state = state;
        }

        public String getName() {
            return name;
        }

        public String getState() {
            return state;
        }

        @Override
        public String toString() {
            return name + " (" + state + ")";
        }
    }

    /**
     * 检查系统中所有包含 "mysql" 关键词的服务
     * @return 返回匹配到的服务列表
     */
    public static List<ServiceInfo> checkMysqlServices() {
        List<ServiceInfo> result = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "sc query type= service state= all");
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes(), "GBK"); // Windows 默认 GBK 编码
            String[] lines = output.split("\\R");

            String currentService = null;
            for (String line : lines) {
                line = line.trim();

                // 找到服务名
                if (line.startsWith("SERVICE_NAME:")) {
                    currentService = line.substring("SERVICE_NAME:".length()).trim();

                    // 判断是否包含 mysql
                    if (!currentService.toLowerCase().contains("mysql")) {
                        currentService = null;
                    }
                }

                // 如果是 mysql 服务，解析状态
                if (currentService != null && line.startsWith("STATE")) {
                    String state;
                    if (line.contains("RUNNING")) {
                        state = "正在运行";
                    } else if (line.contains("STOPPED")) {
                        state = "已安装但未运行";
                    } else {
                        state = "未知";
                    }
                    result.add(new ServiceInfo(currentService, state));
                    currentService = null;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * 删除指定 MySQL 服务
     */
    public static boolean uninstallService(String serviceName) {
        try {
            // 停止服务
            Process stop = new ProcessBuilder("cmd.exe", "/c", "net stop " + serviceName).start();
            stop.waitFor();

            // 删除服务
            Process delete = new ProcessBuilder("cmd.exe", "/c", "sc delete " + serviceName).start();
            int code = delete.waitFor();
            return code == 0;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }
}
