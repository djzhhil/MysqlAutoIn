package com.example.mysqlautoin;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

public class MySQLInstallerUI extends Application {

    private TextField zipPathField;
    private TextField installPathField;
    private PasswordField rootPasswordField;
    private TextField portField;
    private TextArea logArea;
    private ComboBox<WindowsServiceChecker.ServiceInfo> serviceComboBox;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("MySQL 安装助手");

        // ZIP 文件选择
        Label zipLabel = new Label("MySQL ZIP 包：");
        zipPathField = new TextField();
        zipPathField.setEditable(false);
        Button selectZipButton = new Button("选择文件");
        selectZipButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("选择 MySQL ZIP 文件");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 文件", "*.zip"));
            File selectedFile = fileChooser.showOpenDialog(primaryStage);
            if (selectedFile != null) zipPathField.setText(selectedFile.getAbsolutePath());
        });
        HBox zipBox = new HBox(10, zipLabel, zipPathField, selectZipButton);

        // 安装目录
        Label pathLabel = new Label("安装目录：");
        installPathField = new TextField("D:\\mysql");
        Button browseButton = new Button("浏览...");
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择安装目录");
            File dir = chooser.showDialog(primaryStage);
            if (dir != null) installPathField.setText(dir.getAbsolutePath());
        });
        HBox pathBox = new HBox(10, pathLabel, installPathField, browseButton);

        // root 密码
        Label passwordLabel = new Label("root 密码：");
        rootPasswordField = new PasswordField();
        rootPasswordField.setPromptText("请输入 root 密码");
        HBox passwordBox = new HBox(10, passwordLabel, rootPasswordField);

        // 端口号
        Label portLabel = new Label("端口号：");
        portField = new TextField("3306");
        HBox portBox = new HBox(10, portLabel, portField);

        // 日志
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(200);

        // 服务卸载 ComboBox
        serviceComboBox = new ComboBox<>();
        Button refreshButton = new Button("刷新服务列表");
        Button uninstallButton = new Button("卸载选中服务");
        HBox serviceBox = new HBox(10, new Label("已安装 MySQL 服务："), serviceComboBox, refreshButton, uninstallButton);

        refreshButton.setOnAction(e -> {
            logArea.appendText("⏳ 检查 MySQL 服务...\n");
            Task<List<WindowsServiceChecker.ServiceInfo>> task = new Task<>() {
                @Override
                protected List<WindowsServiceChecker.ServiceInfo> call() {
                    return WindowsServiceChecker.checkMysqlServices();
                }
            };
            task.setOnSucceeded(ev -> {
                List<WindowsServiceChecker.ServiceInfo> services = task.getValue();
                serviceComboBox.getItems().clear();
                serviceComboBox.getItems().addAll(services);
                logArea.appendText("✅ 检查完成，共发现 " + services.size() + " 个 MySQL 服务\n");
            });
            new Thread(task).start();
        });

        uninstallButton.setOnAction(e -> {
            WindowsServiceChecker.ServiceInfo service = serviceComboBox.getSelectionModel().getSelectedItem();
            if (service == null) {
                logArea.appendText("❌ 请先选择要卸载的服务\n");
                return;
            }
            boolean success = WindowsServiceChecker.uninstallService(service.getName());
            logArea.appendText(success ? "✅ 服务已删除: " + service.getName() + "\n"
                    : "❌ 删除服务失败: " + service.getName() + "\n");
        });

        // 安装按钮
        Button installButton = new Button("开始安装");
        installButton.setOnAction(e -> {
            String zipPath = zipPathField.getText().trim();
            if (zipPath.isEmpty() || !new File(zipPath).exists()) {
                logArea.appendText("❌ 请先选择 MySQL ZIP 文件！\n");
                return;
            }
            String installPath = installPathField.getText().trim();
            String rootPassword = rootPasswordField.getText().trim();
            String port = portField.getText().trim();

            MySQLInstaller.install(zipPath, installPath, rootPassword, port, msg ->
                    javafx.application.Platform.runLater(() -> {
                        logArea.appendText(msg);
                        logArea.setScrollTop(Double.MAX_VALUE);
                    })
            );
        });

        Button exitButton = new Button("退出");
        exitButton.setOnAction(e -> primaryStage.close());

        HBox buttonBox = new HBox(10, installButton, exitButton);

        VBox root = new VBox(10, zipBox, pathBox, passwordBox, portBox, logArea, serviceBox, buttonBox);
        root.setPadding(new Insets(15));

        primaryStage.setScene(new Scene(root, 800, 500));
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
