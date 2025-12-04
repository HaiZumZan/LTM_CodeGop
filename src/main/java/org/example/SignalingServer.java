package org.example;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SignalingServer extends WebSocketServer {

    // Map: Tên phòng -> Danh sách các kết nối trong phòng
    private final Map<String, Set<WebSocket>> rooms = new ConcurrentHashMap<>();

    // Các Map ánh xạ ngược để tra cứu nhanh
    private final Map<WebSocket, String> socketRoomMap = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> socketIdMap = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> socketNameMap = new ConcurrentHashMap<>();

    // Quản lý user online toàn hệ thống
    private final Map<String, WebSocket> onlineUsers = new ConcurrentHashMap<>();

    private final DatabaseManager dbManager;

    public SignalingServer(int port) {
        super(new InetSocketAddress(port));
        this.dbManager = new DatabaseManager();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[NEW CONN] " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        handleDisconnect(conn);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if(conn != null) handleDisconnect(conn);
    }

    @Override
    public void onStart() {
        System.out.println(">>> SERVER STARTED ON PORT: " + getPort());
    }

    private synchronized void handleDisconnect(WebSocket conn) {
        try {
            String room = socketRoomMap.get(conn);
            String username = socketNameMap.get(conn);
            String userId = socketIdMap.get(conn);

            if (username != null && onlineUsers.get(username) == conn) {
                onlineUsers.remove(username);
            }

            if (room != null) {
                Set<WebSocket> clients = rooms.get(room);
                if (clients != null) {
                    clients.remove(conn);

                    if (userId != null) {
                        // Báo cho mọi người biết user này đã thoát
                        JSONObject leaveMsg = new JSONObject();
                        leaveMsg.put("type", "user_left");
                        leaveMsg.put("id", userId);
                        broadcastToRoom(room, leaveMsg.toString(), conn);
                    }

                    if (clients.isEmpty()) {
                        rooms.remove(room);
                        System.out.println("[ROOM CLOSED] " + room);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            socketRoomMap.remove(conn);
            socketIdMap.remove(conn);
            socketNameMap.remove(conn);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JSONObject data = new JSONObject(message);
            String type = data.optString("type");
            String username = data.optString("username");

            // --- 1. LOGIN / REGISTER ---
            if ("login".equals(type)) {
                String password = data.optString("password");
                boolean success = "KEEP_ALIVE_SESSION".equals(password) || dbManager.loginUser(username, password);
                JSONObject response = new JSONObject();

                if (success) {
                    synchronized (onlineUsers) {
                        WebSocket old = onlineUsers.get(username);
                        if (old != null && old.isOpen() && old != conn) {
                            JSONObject k = new JSONObject();
                            k.put("type", "force_logout");
                            k.put("reason", "Đăng nhập từ nơi khác.");
                            old.send(k.toString());
                            old.close();
                        }
                        onlineUsers.put(username, conn);
                        socketNameMap.put(conn, username);
                    }
                    response.put("type", "login_success");
                    response.put("username", username);
                } else {
                    response.put("type", "login_fail");
                    response.put("reason", "Sai thông tin.");
                }
                conn.send(response.toString());
                return;
            }

            if ("register".equals(type)) {
                boolean s = dbManager.registerUser(username, data.getString("password"));
                JSONObject r = new JSONObject();
                r.put("type", s ? "register_success" : "register_fail");
                conn.send(r.toString());
                return;
            }

            // --- 2. JOIN ROOM (CHẶN TRÙNG SENDER) ---
            String room = data.optString("room");
            if (room.isEmpty()) return;

            if ("join".equals(type)) {
                String id = data.optString("id");

                synchronized (rooms) {
                    rooms.computeIfAbsent(room, k -> Collections.synchronizedSet(new HashSet<>()));
                    Set<WebSocket> clients = rooms.get(room);

                    // Kiểm tra nếu ID là sender mà phòng đã có sender rồi
                    if ("sender".equals(id)) {
                        for (WebSocket c : clients) {
                            if ("sender".equals(socketIdMap.get(c))) {
                                JSONObject err = new JSONObject();
                                err.put("type", "join_fail");
                                err.put("reason", "Phòng đã có người chia sẻ!");
                                conn.send(err.toString());
                                return;
                            }
                        }
                    }

                    clients.add(conn);
                    socketRoomMap.put(conn, room);
                    socketIdMap.put(conn, id);
                    if (!socketNameMap.containsKey(conn)) socketNameMap.put(conn, username);

                    // Gửi danh sách người hiện có cho người mới
                    JSONArray usersList = new JSONArray();
                    for (WebSocket client : clients) {
                        if (client != conn) {
                            JSONObject u = new JSONObject();
                            u.put("id", socketIdMap.get(client));
                            u.put("username", socketNameMap.get(client));
                            usersList.put(u);
                            client.send(message); // Báo cho người cũ có người mới vào
                        }
                    }
                    JSONObject roomInfo = new JSONObject();
                    roomInfo.put("type", "room_users");
                    roomInfo.put("users", usersList);
                    conn.send(roomInfo.toString());
                }
                return;
            }

            // --- 3. LOGIC CHUYỂN QUYỀN (SWAP) ---
            // Viewer xin chia sẻ -> Gửi cho Sender
            if ("request_share".equals(type)) {
                broadcastToSender(room, message);
                return;
            }

            // Sender đồng ý -> Server điều phối đổi vai
            if ("swap_accept".equals(type)) {
                String targetId = data.getString("targetId"); // ID của Viewer được chọn
                Set<WebSocket> clients = rooms.get(room);

                if (clients != null) {
                    synchronized (clients) {
                        WebSocket newSenderWs = null;
                        for(WebSocket c : clients) {
                            if(targetId.equals(socketIdMap.get(c))) { newSenderWs = c; break; }
                        }

                        if(newSenderWs != null) {
                            // A. Bảo Sender cũ (conn hiện tại) chuyển sang view
                            JSONObject toOld = new JSONObject(); toOld.put("type", "grant_view");
                            conn.send(toOld.toString());

                            // B. Bảo Viewer (newSenderWs) chuyển sang share
                            JSONObject toNew = new JSONObject(); toNew.put("type", "grant_share");
                            newSenderWs.send(toNew.toString());

                            // C. Quan trọng: Đổi ID của sender cũ ngay lập tức để giải phóng ID "sender"
                            socketIdMap.put(conn, "v_retired_" + System.currentTimeMillis());
                        }
                    }
                }
                return;
            }

            if ("kick_user".equals(type)) {
                String targetId = data.getString("targetId");
                Set<WebSocket> clients = rooms.get(room);
                if (clients != null) {
                    for(WebSocket c : clients) {
                        if(targetId.equals(socketIdMap.get(c))) {
                            JSONObject k = new JSONObject(); k.put("type", "force_logout"); k.put("reason", "Bạn bị kick.");
                            c.send(k.toString()); c.close(); break;
                        }
                    }
                }
                return;
            }

            // --- 4. ROUTING (UNICAST vs BROADCAST) ---
            String targetId = data.optString("target");
            if (targetId.isEmpty()) targetId = data.optString("targetId");

            // Nếu có target -> Gửi đích danh (Fix lỗi nhiều người xem)
            if (!targetId.isEmpty()) {
                Set<WebSocket> clients = rooms.get(room);
                if (clients != null) {
                    synchronized (clients) {
                        for (WebSocket client : clients) {
                            if (targetId.equals(socketIdMap.get(client))) {
                                client.send(message);
                                return;
                            }
                        }
                    }
                }
                return;
            }

            // Nếu không có target -> Broadcast (Chat, Status...)
            broadcastToRoom(room, message, conn);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void broadcastToRoom(String room, String text, WebSocket sender) {
        Set<WebSocket> clients = rooms.get(room);
        if (clients != null) {
            synchronized (clients) {
                for (WebSocket client : clients) {
                    if (client != sender && client.isOpen()) client.send(text);
                }
            }
        }
    }

    private void broadcastToSender(String room, String text) {
        Set<WebSocket> clients = rooms.get(room);
        if (clients != null) {
            synchronized (clients) {
                for (WebSocket client : clients) {
                    if ("sender".equals(socketIdMap.get(client))) {
                        client.send(text);
                        break;
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        new SignalingServer(3001).start();
    }
}