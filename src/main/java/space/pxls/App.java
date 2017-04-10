package space.pxls;

import com.google.gson.Gson;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import space.pxls.data.Database;
import space.pxls.user.User;
import space.pxls.server.UndertowServer;
import space.pxls.user.UserManager;
import space.pxls.util.Timer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class App {
    private static Gson gson;
    private static Config config;
    private static Database database;
    private static UserManager userManager;
    public static Logger pixelLogger;

    private static int width;
    private static int height;
    private static byte[] board;

    private static Timer mapSaveTimer;
    private static Timer mapBackupTimer;

    public static void main(String[] args) {
        gson = new Gson();

        loadConfig();
        loadMap();

        pixelLogger = LogManager.getLogger("Pixels");

        width = config.getInt("board.width");
        height = config.getInt("board.height");
        if (board == null) board = new byte[width * height];
        else if (board.length != width * height)
        {
            // TODO: Save should contain current board size for adequate relocation of data.
            byte[] newBoard = new byte[width * height];
            int min = newBoard.length < board.length ? newBoard.length : board.length;
            while (min >= 0)
            {
                newBoard[min] = board[min];
                min++;
            }
        }
        
        database = new Database();
        userManager = new UserManager();

        new UndertowServer(config.getInt("server.port")).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            saveMapBackup();
            saveMapForce();
        }));
        saveMap();
    }

    private static void loadConfig() {
        config = ConfigFactory.parseFile(new File("pxls.conf")).withFallback(ConfigFactory.load());
        config.checkValid(ConfigFactory.load());

        mapSaveTimer = new Timer(config.getDuration("board.saveInterval", TimeUnit.SECONDS));
        mapBackupTimer = new Timer(config.getDuration("board.backupInterval", TimeUnit.SECONDS));
    }
    
    public static Boolean reloadConfig()
    {
        try
        {
            Config newCfg = ConfigFactory.parseFile(new File("pxls.conf")).withFallback(ConfigFactory.load());
            newCfg.checkValid(ConfigFactory.load());
            config = newCfg;
            pixelLogger.log(Level.INFO, "Reloaded config file");
        }
        catch (ConfigException e)
        {
            return false;
        }
        return true;
    }


    public static Gson getGson() {
        return gson;
    }

    public static Config getConfig() {
        return config;
    }

    public static int getWidth() {
        return width;
    }

    public static int getHeight() {
        return height;
    }

    public static byte[] getBoardData() {
        return board;
    }

    public static Path getStorageDir() {
        return Paths.get(config.getString("server.storage"));
    }

    public static List<String> getPalette() {
        return config.getStringList("board.palette");
    }

    public static boolean isCaptchaEnabled() {
        return config.hasPath("captcha.key") && config.hasPath("captcha.secret");
    }

    public static void blank(User user, int x1, int y1, int x2, int y2, int to, int from)
    {
        saveMapBackup();
        saveMapForce();
        // Would use optimization
        
        if (to < 0 || to >= getPalette().size() || from >= getPalette().size()) return;
        if (from < 0) from = -1; // Clamp to -1
        // Clamp
        if (x1 < 0) x1 = 0;
        else if (x1 >= width) x1 = width - 1;
        if (y1 < 0) y1 = 0;
        else if (y1 >= height) y1 = height - 1;
        
        if (x2 < 0) x2 = 0;
        else if (x2 >= width) x2 = width - 1;
        if (y2 < 0) y2 = 0;
        else if (y2 >= height) y2 = height - 1;
        
        // Swap
        if (x2 < x1)
        {
            int tmp = x2;
            x2 = x1;
            x1 = tmp;
        }
        if (y2 < y1)
        {
            int tmp = y2;
            y2 = y1;
            y1 = tmp;
        }
        
        int pos;
        int x;
        int y = y1;
        byte bto = (byte) to;
        int who = user.getId();
        while (y <= y2)
        {
            pos = y * width + x1;
            x = x1;
            while (x <= x2)
            {
                if (from == -1 || board[pos + x] == (byte) from)
                {
                    board[pos + x] = bto;
                    database.blankStep(who, x, y, bto);
                }
                x++;
            }
            y++;
        }
        pixelLogger.log(Level.INFO, user.getName() + " Blank operation: " + x1 + " " + y1 + " > " + x2 + " " + y2 + " : " + (from == -1 ? to : from + " => " + to));
        database.blankFinish(who);
    }
    public static void putPixel(int x, int y, int color, User user) {
        if (x < 0 || x >= width || y < 0 || y >= height || color < 0 || color >= getPalette().size()) return;
        board[x + y * width] = (byte) color;
        pixelLogger.log(Level.INFO, user.getName() + " " + x + " " + y + " " + color);
        database.placePixel(x, y, color, user);
    }

    private static void loadMap() {
        try {
            Path boardPath = getStorageDir().resolve("board.dat");
            if (Files.exists(boardPath))
                board = Files.readAllBytes(boardPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void saveMap() {
        mapSaveTimer.run(App::saveMapForce);
        mapBackupTimer.run(App::saveMapBackup);
    }

    private static void saveMapForce() {
        saveMapToDir(getStorageDir().resolve("board.dat"));
    }

    private static void saveMapBackup() {
        saveMapToDir(getStorageDir().resolve("backups/board." + System.currentTimeMillis() + ".dat"));
    }

    private static void saveMapToDir(Path path) {
        try {
            Files.write(path, board);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static UserManager getUserManager() {
        return userManager;
    }

    public static Database getDatabase() {
        return database;
    }
}
