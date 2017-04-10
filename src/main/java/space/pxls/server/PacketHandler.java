package space.pxls.server;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.async.Callback;
import com.mashape.unirest.http.exceptions.UnirestException;
import io.undertow.websockets.core.WebSocketChannel;
import space.pxls.App;
import space.pxls.user.Role;
import space.pxls.user.User;
import space.pxls.util.Timer;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.spec.EllipticCurve;
import java.util.Collections;

public class PacketHandler {
    private UndertowServer server;
    private Timer userData = new Timer(5);

    public PacketHandler(UndertowServer server) {
        this.server = server;
    }

    public void connect(WebSocketChannel channel, User user) {
        if (user != null) {
            server.send(channel, new Packet.UserInfo(user.getName()));
            sendCooldownData(channel, user);
            user.flagForCaptcha();
        }

        updateUserData();
    }

    public void disconnect(WebSocketChannel channel, User user) {
        updateUserData();
    }

    public void accept(WebSocketChannel channel, User user, Object obj) {
        if (user != null) {
            if (obj instanceof Packet.ClientPlace) handlePlace(channel, user, ((Packet.ClientPlace) obj));
            if (obj instanceof Packet.ClientCaptcha) handleCaptcha(channel, user, ((Packet.ClientCaptcha) obj));
            if (obj instanceof Packet.ClientAdminCommand && user.getRole().greaterEqual(Role.ADMIN)) handleCommand(channel, user, (Packet.ClientAdminCommand) obj);
        }
    }

    private void handlePlace(WebSocketChannel channel, User user, Packet.ClientPlace cp) {
        if (cp.x < 0 || cp.x >= App.getWidth() || cp.y < 0 || cp.y >= App.getHeight()) return;
        if (cp.color < 0 || cp.color >= App.getConfig().getStringList("board.palette").size()) return;
        if (user.canPlace()) {
            if (user.updateCaptchaFlagPrePlace()) {
                server.send(channel, new Packet.ServerCaptchaRequired());
            } else {
                App.putPixel(cp.x, cp.y, cp.color, user);
                App.saveMap();
                broadcastPixelUpdate(cp.x, cp.y, cp.color);

                if (!user.isOverridingCooldown())
                    user.resetCooldown();
            }
        }

        sendCooldownData(channel, user);
    }

    private void handleCaptcha(WebSocketChannel channel, User user, Packet.ClientCaptcha cc) {
        if (!user.isFlaggedForCaptcha()) return;

        Unirest
                .post("https://www.google.com/recaptcha/api/siteverify")
                .field("secret", App.getConfig().getString("captcha.secret"))
                .field("response", cc.token)
                .field("remoteip", "null")
                .asJsonAsync(new Callback<JsonNode>() {
                    @Override
                    public void completed(HttpResponse<JsonNode> response) {
                        JsonNode body = response.getBody();

                        String hostname = App.getConfig().getString("captcha.host");

                        boolean success = body.getObject().getBoolean("success") && body.getObject().getString("hostname").equals(hostname);
                        if (success) {
                            user.validateCaptcha();
                        }

                        server.send(channel, new Packet.ServerCaptchaStatus(success));
                    }

                    @Override
                    public void failed(UnirestException e) {

                    }

                    @Override
                    public void cancelled() {

                    }
                });
    }

    private void handleCommand(WebSocketChannel channel, User user, Packet.ClientAdminCommand cmd) {
        switch (cmd.command)
        {
            case "alert":
                if (cmd.arguments.length == 0) return; // Empty message.
                
                StringBuffer buf = new StringBuffer();
                buf.append(cmd.arguments[0]);
                int i = 1;
                while (i < cmd.arguments.length)
                {
                    buf.append(" ");
                    buf.append(cmd.arguments[i++]);
                }
                server.broadcast(new Packet.ServerAlert(buf.toString()));
                break;
            case "blank":
                if (cmd.arguments.length >= 5)
                {
                    int from = cmd.arguments.length >= 6 ? Integer.parseInt(cmd.arguments[5]) : -1;
                    App.blank(user, Integer.parseInt(cmd.arguments[0]), Integer.parseInt(cmd.arguments[1]), Integer.parseInt(cmd.arguments[2]),
                                                                        Integer.parseInt(cmd.arguments[3]), Integer.parseInt(cmd.arguments[4]), from);
                }
                else
                {
                    server.send(channel, new Packet.ServerAlert("Invalid amount of argument for `blank` command!"));
                }
                break;
            case "reload":
                if (!App.reloadConfig())
                {
                    server.send(channel, new Packet.ServerAlert("Error occured during config reloading!"));
                }
                break;
            case "save":
                App.saveMap();
                break;
            // TODO: Edit palette.
            default:
                server.send(channel, new Packet.ServerAlert("Unknown command!"));
        }
    }
    
    private void updateUserData() {
        userData.run(() -> {
            server.broadcast(new Packet.ServerUsers(server.getConnections().size()));
        });
    }

    private void sendCooldownData(WebSocketChannel channel, User user) {
        server.send(channel, new Packet.ServerCooldown(user.getRemainingCooldown()));
    }

    private void broadcastPixelUpdate(int x, int y, int color) {
        server.broadcast(new Packet.ServerPlace(Collections.singleton(new Packet.ServerPlace.Pixel(x, y, color))));
    }
}
