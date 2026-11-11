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
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "wither_aura.json");

    // 1. Chọn mục tiêu
    public String priorityMode = "DISTANCE"; // "DISTANCE" hoặc "HEALTH"
    public double minRange = 2.65;           // Tầm đánh biến thiên liên tục từ min -> max
    public double maxRange = 2.96;           // Không bao giờ vượt quá 3.0 block vanilla
    public float minFov = 95.0f;             // Góc FOV cũng dao động ngẫu nhiên
    public float maxFov = 115.0f;

    // 2. Tấn công & Đổi vũ khí (Random Delay)
    public boolean autoSwitchSword = true;
    public int minSwitchDelayMs = 85;        // Độ trễ trước và sau khi rút kiếm Looting
    public int maxSwitchDelayMs = 230;
    public boolean sprintReset = true;
    public double sprintResetChance = 0.68;  // Chỉ W-tap 68% số đòn để tránh lộ pattern
    public int minSprintHoldMs = 55;         // Thời gian nhả nút chạy ngẫu nhiên (ms)
    public int maxSprintHoldMs = 145;
    public boolean preferCrits = false;

    // 3. Xoay đầu (Smooth + Random Pause + Drift)
    public float minYawSpeed = 9.5f;
    public float maxYawSpeed = 26.0f;
    public float minPitchSpeed = 6.0f;
    public float maxPitchSpeed = 17.5f;
    public double hitboxRandomOffset = 0.19;
    public int minAimShiftMs = 140;          // Thời gian giữa mỗi lần đổi điểm ngắm trên người quái
    public int maxAimShiftMs = 420;
    public double rotationMicroPauseChance = 0.07; // 7% cơ hội khựng chuột cực nhẹ khi đang xoay

    // 4. Random Delay toàn diện (Gaussian + Biến thiên)
    public int meanAttackDelayMs = 90;       // Độ trễ trung bình sau khi đầy thanh cooldown
    public int stdDevAttackDelayMs = 48;     // Độ lệch chuẩn Gaussian
    public float minCooldownThreshold = 0.96f; // Đôi khi đánh ở 96%-100% thanh thay vì luôn cứng nhắc 100.0%
    public int minReactionMs = 170;          // Độ trễ khi thấy Wither Skeleton mới
    public int maxReactionMs = 390;
    public int minPostKillDelayMs = 130;     // Độ trễ thu tay sau khi vừa giết 1 con
    public int maxPostKillDelayMs = 360;
    public double intentionalMissChance = 0.04; // 4% tỉ lệ trễ nhịp/vung tay hụt chủ đích
    public int minKillsBeforeBreak = 11;
    public int maxKillsBeforeBreak = 24;
    public int minBreakMs = 1800;
    public int maxBreakMs = 5200;

    // 5. An toàn
    public float minHealth = 8.0f;
    public double playerPanicRadius = 25.0;
    public int minWeaponDurability = 15;
    public boolean silentMode = true;

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
