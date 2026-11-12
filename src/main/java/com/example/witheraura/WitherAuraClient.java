package com.example.witheraura;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "wither_aura_v2.json");

    // 1. Chọn mục tiêu
    public boolean onlyWitherSkeleton = true; // Bấm phím O trong game để đổi nhanh chế độ Test
    public String priorityMode = "DISTANCE";  // "DISTANCE" hoặc "HEALTH"
    public double minRange = 2.90;            // Tầm đánh biến thiên chuẩn vanilla (~3 block)
    public double maxRange = 3.05;
    public float minFov = 140.0f;             // Góc quét rộng để không bỏ sót quái trước mặt
    public float maxFov = 180.0f;

    // 2. Tấn công & Đổi vũ khí (Random Delay)
    public boolean autoSwitchSword = true;
    public int minSwitchDelayMs = 80;
    public int maxSwitchDelayMs = 210;
    public boolean sprintReset = true;
    public double sprintResetChance = 0.65;
    public int minSprintHoldMs = 55;
    public int maxSprintHoldMs = 140;
    public boolean preferCrits = false;

    // 3. Xoay đầu (Smooth + Random Pause + Drift)
    public float minYawSpeed = 14.0f;
    public float maxYawSpeed = 32.0f;
    public float minPitchSpeed = 10.0f;
    public float maxPitchSpeed = 22.0f;
    public double hitboxRandomOffset = 0.16;
    public int minAimShiftMs = 150;
    public int maxAimShiftMs = 400;
    public double rotationMicroPauseChance = 0.05;

    // 4. Random Delay toàn diện (Gaussian + Biến thiên)
    public int meanAttackDelayMs = 75;
    public int stdDevAttackDelayMs = 40;
    public float minCooldownThreshold = 0.95f;
    public int minReactionMs = 120;
    public int maxReactionMs = 280;
    public int minPostKillDelayMs = 100;
    public int maxPostKillDelayMs = 290;
    public double intentionalMissChance = 0.03;
    public int minKillsBeforeBreak = 14;
    public int maxKillsBeforeBreak = 26;
    public int minBreakMs = 1500;
    public int maxBreakMs = 4000;

    // 5. An toàn
    public float minHealth = 6.0f;            // Tự dừng khi máu <= 3 tim (6.0f)
    public boolean pauseWhenPlayerNearby = false; // Mặc định tắt để không bị Bot Anti-Cheat làm kẹt
    public double playerPanicRadius = 15.0;
    public int minWeaponDurability = 10;
    public boolean silentMode = false;        // Mặc định HIỆN thông báo khi bấm phím để dễ kiểm tra

    public static ModConfig load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                return GSON.fromJson(reader, ModConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        ModConfig config = new ModConfig();
        config.save();
        return config;
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
