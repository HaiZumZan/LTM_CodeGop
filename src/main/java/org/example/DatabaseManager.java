package org.example;

import java.sql.*;

public class DatabaseManager {
    private Connection connection;

    public DatabaseManager() {
        try {
            Class.forName("org.sqlite.JDBC");
            // Tự động tạo file database.db nếu chưa có
            String url = "jdbc:sqlite:database.db";
            connection = DriverManager.getConnection(url);
            createTable();
            System.out.println("Database: Connected/Created 'database.db'");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createTable() {
        String sql = "CREATE TABLE IF NOT EXISTS users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "username TEXT NOT NULL UNIQUE," +
                "password TEXT NOT NULL)";
        try (Statement stmt = connection.createStatement()) { stmt.execute(sql); }
        catch (SQLException e) { e.printStackTrace(); }
    }

    public boolean registerUser(String username, String password) {
        String sql = "INSERT INTO users(username, password) VALUES(?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.setString(2, password);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) { return false; }
    }

    public boolean loginUser(String username, String password) {
        String sql = "SELECT id FROM users WHERE username = ? AND password = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.setString(2, password);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        } catch (SQLException e) { return false; }
    }

    public boolean changePassword(String username, String oldPass, String newPass) {
        if (!loginUser(username, oldPass)) return false;
        String sql = "UPDATE users SET password = ? WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, newPass);
            pstmt.setString(2, username);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) { return false; }
    }

    public boolean changeUsername(String currentName, String password, String newName) {
        if (!loginUser(currentName, password)) return false;
        String sql = "UPDATE users SET username = ? WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, newName);
            pstmt.setString(2, currentName);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) { return false; }
    }
}