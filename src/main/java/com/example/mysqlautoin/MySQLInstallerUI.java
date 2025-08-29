package com.example.mysqlautoin;

import javafx.application.Application;
import javafx.application.Platform;
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
    private PasswordField rootPasswordField;
    private TextField portField;
    private TextArea logArea;
    private ComboBox<WindowsServiceChecker.ServiceInfo> serviceComboBox;
    private CheckBox envCheckBox;
    private boolean isAdmin;
    private ProgressIndicator progressIndicator;
    private Label statusLabel;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("MySQL 安装助手");

        isAdmin = WindowsServiceChecker.isAdmin();

        // 创建主布局
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));

        // 创建选项卡面板
        TabPane tabPane = new TabPane();

        // 安装选项卡
        Tab installTab = new Tab("安装 MySQL");
        installTab.setClosable(false);
        installTab.setContent(createInstallTabContent(primaryStage));

        // 管理选项卡
        Tab manageTab = new Tab("管理服务");
        manageTab.setClosable(false);
        manageTab.setContent(createManageTabContent());

        tabPane.getTabs().addAll(installTab, manageTab);

        // 日志区域
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(200);
        logArea.setStyle("-fx-font-family: 'Consolas', monospace;");

        // 进度指示器和状态标签
        progressIndicator = new ProgressIndicator();
        progressIndicator.setVisible(false);
        progressIndicator.setPrefSize(20, 20);

        statusLabel = new Label("就绪");
        HBox statusBox = new HBox(10, progressIndicator, statusLabel);
        statusBox.setPadding(new Insets(5, 0, 0, 0));

        if (!isAdmin) {
            logArea.appendText("⚠️ 当前非管理员，某些操作将无法执行\n");
        }

        root.getChildren().addAll(tabPane, logArea, statusBox);

        // 自动刷新服务列表
        refreshServiceList();

        primaryStage.setScene(new Scene(root, 900, 600));
        primaryStage.show();
    }

    private VBox createInstallTabContent(Stage primaryStage) {
        VBox content = new VBox(10);

        // ZIP文件选择
        zipPathField = new TextField();
        zipPathField.setEditable(false);
        Button selectZipButton = new Button("选择ZIP文件");
        selectZipButton.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP 文件", "*.zip"));
            File f = fc.showOpenDialog(primaryStage);
            if (f != null) {
                zipPathField.setText(f.getAbsolutePath());
                logArea.appendText("✅ 已选择 ZIP 文件: " + f.getAbsolutePath() + "\n");
            }
        });

        HBox zipBox = new HBox(10, new Label("MySQL ZIP 包："), zipPathField, selectZipButton);

        // 密码输入
        rootPasswordField = new PasswordField();
        rootPasswordField.setPromptText("请输入 root 密码");
        HBox passwordBox = new HBox(10, new Label("root 密码："), rootPasswordField);

        // 端口输入
        portField = new TextField("3306");
        HBox portBox = new HBox(10, new Label("端口号："), portField);

        // 环境变量选项
        envCheckBox = new CheckBox("自动配置环境变量");
        envCheckBox.setSelected(true);
        if (!isAdmin) {
            envCheckBox.setSelected(false);
            envCheckBox.setDisable(true);
            envCheckBox.setTooltip(new Tooltip("需要管理员权限"));
        }

        // 安装按钮
        Button installButton = new Button("开始安装");
        installButton.setStyle("-fx-font-weight: bold; -fx-background-color: #2E8B57; -fx-text-fill: white;");
        installButton.setOnAction(e -> startInstallation(primaryStage));

        content.getChildren().addAll(zipBox, passwordBox, portBox, envCheckBox, installButton);
        return content;
    }

    private VBox createManageTabContent() {
        VBox content = new VBox(10);

        // 服务选择
        serviceComboBox = new ComboBox<>();
        serviceComboBox.setPrefWidth(250);
        serviceComboBox.setTooltip(new Tooltip("选择要管理的MySQL服务"));

        Button refreshButton = new Button("刷新服务列表");
        refreshButton.setOnAction(e -> refreshServiceList());

        Button uninstallButton = new Button("卸载选中服务");
        uninstallButton.setStyle("-fx-background-color: #DC143C; -fx-text-fill: white;");
        if (!isAdmin) uninstallButton.setDisable(true);
        uninstallButton.setOnAction(e -> uninstallSelectedService());

        HBox serviceBox = new HBox(10, new Label("已安装 MySQL 服务："), serviceComboBox, refreshButton, uninstallButton);

        // 服务操作按钮
        Button startButton = new Button("启动服务");
        startButton.setOnAction(e -> {
            WindowsServiceChecker.ServiceInfo service = serviceComboBox.getSelectionModel().getSelectedItem();
            if (service != null) {
                startStopService(service.getName(), true);
            }
        });

        Button stopButton = new Button("停止服务");
        stopButton.setOnAction(e -> {
            WindowsServiceChecker.ServiceInfo service = serviceComboBox.getSelectionModel().getSelectedItem();
            if (service != null) {
                startStopService(service.getName(), false);
            }
        });

        HBox serviceActions = new HBox(10, startButton, stopButton);

        content.getChildren().addAll(serviceBox, serviceActions);
        return content;
    }

    private void startInstallation(Stage primaryStage) {
        String zip = zipPathField.getText().trim();
        String pwd = rootPasswordField.getText().trim();
        String port = portField.getText().trim();
        boolean env = envCheckBox.isSelected();

        if (zip.isEmpty() || !new File(zip).exists()) {
            logArea.appendText("❌ 请先选择有效的 ZIP 文件\n");
            return;
        }

        if (pwd.isEmpty()) {
            logArea.appendText("❌ 请输入 root 密码\n");
            return;
        }

        if (port.isEmpty() || !port.matches("\\d+")) {
            logArea.appendText("❌ 请输入有效的端口号\n");
            return;
        }

        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("选择安装目录");
        File selectedDir = dirChooser.showDialog(primaryStage);
        if (selectedDir == null) {
            logArea.appendText("❌ 用户取消安装\n");
            return;
        }

        String installDir = selectedDir.getAbsolutePath();

        // 检查是否已存在相同端口的服务
        for (WindowsServiceChecker.ServiceInfo service : serviceComboBox.getItems()) {
            if (service.getName().endsWith(port)) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("端口冲突");
                alert.setHeaderText("已存在使用端口 " + port + " 的 MySQL 服务");
                alert.setContentText("服务名称: " + service.getName() + "\n\n是否继续安装？这可能会导致端口冲突。");
                alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
                alert.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.YES) {
                        proceedWithInstallation(zip, installDir, pwd, port, env);
                    } else {
                        logArea.appendText("❌ 用户取消安装\n");
                    }
                });
                return;
            }
        }

        proceedWithInstallation(zip, installDir, pwd, port, env);
    }

    private void proceedWithInstallation(String zip, String installDir, String pwd, String port, boolean env) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认安装");
        alert.setHeaderText("确认在目录 '" + installDir + "' 安装 MySQL 吗？");
        alert.setContentText("端口: " + port + "\n服务名称: MySQL" + port);
        alert.showAndWait().ifPresent(resp -> {
            if (resp == ButtonType.OK) {
                setProgress(true, "安装中...");
                Task<Void> task = new Task<>() {
                    @Override
                    protected Void call() {
                        MySQLInstaller.install(zip, installDir, pwd, port, env,
                                msg -> Platform.runLater(() -> logArea.appendText(msg)));
                        return null;
                    }
                };

                task.setOnSucceeded(e -> {
                    setProgress(false, "安装完成");
                    refreshServiceList();
                });

                task.setOnFailed(e -> {
                    setProgress(false, "安装失败");
                    logArea.appendText("❌ 安装过程中发生错误\n");
                });

                new Thread(task).start();
            } else {
                logArea.appendText("❌ 用户取消安装\n");
            }
        });
    }

    private void refreshServiceList() {
        setProgress(true, "刷新服务列表...");
        Task<List<WindowsServiceChecker.ServiceInfo>> task = new Task<>() {
            @Override
            protected List<WindowsServiceChecker.ServiceInfo> call() {
                return WindowsServiceChecker.checkMysqlServices();
            }
        };

        task.setOnSucceeded(e -> {
            serviceComboBox.getItems().clear();
            serviceComboBox.getItems().addAll(task.getValue());

            // 检查是否有MySQL服务但未启动
            for (WindowsServiceChecker.ServiceInfo service : task.getValue()) {
                if ("已停止".equals(service.getState())) {
                    // 询问用户是否要自动启动服务
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.setTitle("服务未启动");
                    alert.setHeaderText("发现未启动的MySQL服务: " + service.getName());
                    alert.setContentText("是否要自动启动该服务？");

                    alert.showAndWait().ifPresent(response -> {
                        if (response == ButtonType.OK) {
                            StringBuilder log = new StringBuilder();
                            WindowsServiceChecker.tryAutoStartMysqlService(service.getName(), log);
                            logArea.appendText(log.toString());
                            // 刷新服务列表以更新状态
                            refreshServiceList();
                        }
                    });
                    break;
                }
            }

            logArea.appendText("✅ 服务列表刷新完成，共发现 " + task.getValue().size() + " 个服务\n");
            setProgress(false, "就绪");
        });

        task.setOnFailed(e -> {
            logArea.appendText("❌ 刷新服务列表失败\n");
            setProgress(false, "就绪");
        });

        new Thread(task).start();
    }

    private void uninstallSelectedService() {
        WindowsServiceChecker.ServiceInfo service = serviceComboBox.getSelectionModel().getSelectedItem();
        if (service == null) {
            logArea.appendText("❌ 请先选择服务\n");
            return;
        }

        // 检查服务状态
        if ("正在运行".equals(service.getState())) {
            Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
            confirmAlert.setTitle("确认停止服务");
            confirmAlert.setHeaderText("服务 '" + service.getName() + "' 正在运行");
            confirmAlert.setContentText("需要先停止服务才能卸载。是否继续？");
            confirmAlert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);

            confirmAlert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.YES) {
                    stopServiceBeforeUninstall(service);
                } else {
                    logArea.appendText("❌ 用户取消卸载\n");
                }
            });
        } else {
            proceedWithUninstall(service);
        }
    }

    private void stopServiceBeforeUninstall(WindowsServiceChecker.ServiceInfo service) {
        setProgress(true, "停止服务...");
        Task<Boolean> stopTask = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                StringBuilder log = new StringBuilder();
                boolean success = WindowsServiceChecker.startStopService(service.getName(), false, log);
                Platform.runLater(() -> logArea.appendText(log.toString()));
                return success;
            }
        };

        stopTask.setOnSucceeded(e -> {
            boolean success = stopTask.getValue();
            if (success) {
                logArea.appendText("✅ 服务已停止: " + service.getName() + "\n");
                proceedWithUninstall(service);
            } else {
                logArea.appendText("❌ 停止服务失败: " + service.getName() + "\n");
                setProgress(false, "就绪");

                // 即使停止失败，也询问用户是否继续卸载
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("停止服务失败");
                alert.setHeaderText("停止服务失败");
                alert.setContentText("是否尝试强制卸载服务？");
                alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);

                alert.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.YES) {
                        proceedWithUninstall(service);
                    } else {
                        logArea.appendText("❌ 用户取消卸载\n");
                    }
                });
            }
        });

        stopTask.setOnFailed(e -> {
            logArea.appendText("❌ 停止服务时发生错误: " + stopTask.getException().getMessage() + "\n");
            setProgress(false, "就绪");
        });

        new Thread(stopTask).start();
    }

    private void proceedWithUninstall(WindowsServiceChecker.ServiceInfo service) {
        // 备份提示
        Alert backupAlert = new Alert(Alert.AlertType.CONFIRMATION);
        backupAlert.setTitle("备份提示");
        backupAlert.setHeaderText("即将卸载服务 '" + service.getName() + "'");

        String dataDir = service.getBinPath() != null ?
                service.getBinPath().replace("\\bin", "\\data") : "未知";

        backupAlert.setContentText("建议先备份数据库目录: " + dataDir +
                "\n\n确认继续卸载？");

        backupAlert.showAndWait().ifPresent(resp -> {
            if (resp == ButtonType.OK) {
                setProgress(true, "卸载服务...");
                Task<Void> uninstallTask = new Task<>() {
                    @Override
                    protected Void call() {
                        StringBuilder log = new StringBuilder();
                        WindowsServiceChecker.uninstallService(service, log);
                        WindowsServiceChecker.deleteInstallDir(service, log);
                        Platform.runLater(() -> logArea.appendText(log.toString()));
                        return null;
                    }
                };

                uninstallTask.setOnSucceeded(e -> {
                    setProgress(false, "卸载完成");
                    refreshServiceList();
                });

                uninstallTask.setOnFailed(e -> {
                    setProgress(false, "卸载失败");
                    logArea.appendText("❌ 卸载过程中发生错误\n");
                });

                new Thread(uninstallTask).start();
            } else {
                logArea.appendText("❌ 用户取消卸载\n");
            }
        });
    }

    private void startStopService(String serviceName, boolean start) {
        setProgress(true, start ? "启动服务..." : "停止服务...");
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                StringBuilder log = new StringBuilder();
                boolean success = WindowsServiceChecker.startStopService(serviceName, start, log);
                Platform.runLater(() -> logArea.appendText(log.toString()));
                return success;
            }
        };

        task.setOnSucceeded(e -> {
            boolean success = task.getValue();
            if (success) {
                logArea.appendText("✅ " + (start ? "启动" : "停止") + "服务成功: " + serviceName + "\n");
            } else {
                logArea.appendText("❌ " + (start ? "启动" : "停止") + "服务失败: " + serviceName + "\n");
            }
            setProgress(false, "就绪");
            refreshServiceList();
        });

        task.setOnFailed(e -> {
            logArea.appendText("❌ " + (start ? "启动" : "停止") + "服务时发生错误: " + task.getException().getMessage() + "\n");
            setProgress(false, "就绪");
        });

        new Thread(task).start();
    }

    private void setProgress(boolean visible, String status) {
        Platform.runLater(() -> {
            progressIndicator.setVisible(visible);
            statusLabel.setText(status);
        });
    }

    public static void main(String[] args) {
        launch();
    }
}