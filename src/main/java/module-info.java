module com.example.mysqlautoin {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.base;
    requires javafx.graphics;
    requires javafx.media;
    requires javafx.swing;
    requires javafx.web;

    opens com.example.mysqlautoin to javafx.fxml;
    exports com.example.mysqlautoin;
}