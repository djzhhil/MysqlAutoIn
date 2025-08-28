package com.example.mysqlautoin;

import java.io.File;

public class MySQLZipFinder {

    /**
     * 查找 MySQL ZIP 文件路径
     * @param userPath 用户输入路径（可为空）
     * @return 找到的 ZIP 文件绝对路径，如果找不到返回 null
     */
    public static String findMySQLZip(String userPath) {
        // 1. 用户指定路径优先
        if (userPath != null && !userPath.isBlank()) {
            File userFile = new File(userPath);
            if (userFile.exists() && userFile.isFile() && userFile.getName().toLowerCase().endsWith(".zip")) {
                return userFile.getAbsolutePath();
            }
        }

        // 2. 项目根目录下
        File projectZip = new File("mysql.zip");
        if (projectZip.exists() && projectZip.isFile()) {
            return projectZip.getAbsolutePath();
        }

        // 3. resources/installer 目录
        File resourceZip = new File("resources/installer/mysql.zip");
        if (resourceZip.exists() && resourceZip.isFile()) {
            return resourceZip.getAbsolutePath();
        }

        // 没有找到
        return null;
    }
}
