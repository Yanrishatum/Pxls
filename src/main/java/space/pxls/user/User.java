package space.pxls.user;

import space.pxls.App;

import java.util.concurrent.TimeUnit;

public class User {
    private int id;
    private String name;
    private String login;

    private Role role = Role.DEFAULT;
    private boolean overrideCooldown;
    private boolean flaggedForCaptcha = true;
    private boolean justShowedCaptcha;
    private long lastPlaceTime;

    public User(int id, String name, String login, long lastPlaceTime, Role role) {
        this.id = id;
        this.name = name;
        this.login = login;
        this.lastPlaceTime = lastPlaceTime;
    }

    public int getId() {
        return id;
    }

    public boolean canPlace() {
        if (role.greaterEqual(Role.MODERATOR) && overrideCooldown) return true;

        int serverCooldown = (int) App.getConfig().getDuration("cooldown", TimeUnit.SECONDS);
        return lastPlaceTime + serverCooldown * 1000 < System.currentTimeMillis();
    }

    public float getRemainingCooldown() {
        if (role.greaterEqual(Role.MODERATOR) && overrideCooldown) return 0;

        int serverCooldown = (int) App.getConfig().getDuration("cooldown", TimeUnit.SECONDS);
        return Math.max(0, lastPlaceTime + serverCooldown * 1000 - System.currentTimeMillis()) / 1000f;
    }

    public boolean updateCaptchaFlagPrePlace() {
        if (flaggedForCaptcha) return true;

        // Disable captchas if they're disabled, duh
        // Also don't show captcha if we *just* had one and haven't had the chance to place yet
        if (!App.isCaptchaEnabled() || justShowedCaptcha) {
            flaggedForCaptcha = false;
            justShowedCaptcha = false;
            return false;
        }

        int captchaThreshold = App.getConfig().getInt("captcha.threshold");
        if (Math.random() < (1f / captchaThreshold)) {
            flaggedForCaptcha = true;
        }

        if (role.greaterThan(Role.DEFAULT)) flaggedForCaptcha = false;

        return flaggedForCaptcha;
    }

    public void setOverrideCooldown(boolean overrideCooldown) {
        this.overrideCooldown = overrideCooldown;
        if (role.lessThan(Role.MODERATOR)) this.overrideCooldown = false;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public void resetCooldown() {
        lastPlaceTime = System.currentTimeMillis();
    }

    public boolean isOverridingCooldown() {
        return overrideCooldown;
    }

    public void validateCaptcha() {
        flaggedForCaptcha = false;
        justShowedCaptcha = true;
    }

    public boolean isFlaggedForCaptcha() {
        return flaggedForCaptcha;
    }

    public void flagForCaptcha() {
        flaggedForCaptcha = true;
    }

    public String getName() {
        return name;
    }
}
