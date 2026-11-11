package com.example.witheraura;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.StreamSupport;

public class WitherAuraClient implements ClientModInitializer {
    private static ModConfig config;
    private static KeyBinding toggleKey;
    private static KeyBinding reloadConfigKey;

    private boolean enabled = false;
    private final Random random = new Random();

    // Trạng thái mục tiêu
    private WitherSkeletonEntity currentTarget = null;
    private Vec3d aimOffset = Vec3d.ZERO;
    private boolean wasTargetAlive = false;

    // Bộ đếm thời gian Random Delay cho mọi hành động
    private long targetAcquiredTime = 0;
    private long currentReactionDelay = 0;
    private long nextRotationStartTime = 0;
    private long rotationPauseUntil = 0;
    private long nextAimShiftTime = 0;
    private long nextAttackTime = 0;
    private long postKillCooldownUntil = 0;
    private long breakEndTime = 0;

    // Random Delay cho đổi kiếm
    private int pendingSwordSlot = -1;
    private long executeSwitchTime = 0;
    private long postSwitchAttackLockUntil = 0;

    // Random Delay cho Sprint-Reset (nhả nút W/Sprint tự nhiên)
    private boolean holdingSprintReset = false;
    private long sprintResumeTime = 0;

    // Biến thiên tầm đánh, FOV và ngưỡng Cooldown mỗi đòn
    private double dynamicRange = 2.85;
    private float dynamicFov = 105.0f;
    private float nextCooldownThreshold = 1.0f;

    // Đếm kill để nghỉ ngơi
    private int killCount = 0;
    private int killsUntilNextBreak = 15;

    @Override
    public void onInitializeClient() {
        config = ModConfig.load();
        resetBreakCounter();
        randomizeDynamicParameters();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.witheraura.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.witheraura"
        ));

        reloadConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.witheraura.reload",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.witheraura"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            resetState();
            if (!config.silentMode && client.player != null) {
                client.player.sendMessage(Text.literal("[WitherAura] " + (enabled ? "ON" : "OFF")), true);
            }
        }

        while (reloadConfigKey.wasPressed()) {
            config = ModConfig.load();
        }

        if (!enabled || client.player == null || client.world == null || client.interactionManager == null) {
            return;
        }

        ClientPlayerEntity player = client.player;
        long now = System.currentTimeMillis();

        // Khôi phục lại Sprint sau khoảng trễ ngẫu nhiên
        if (holdingSprintReset && now >= sprintResumeTime) {
            holdingSprintReset = false;
            if (client.options.forwardKey.isPressed()) {
                player.setSprinting(true);
            }
        }

        // 5. KIỂM TRA AN TOÀN: Tự tắt khi máu thấp hoặc có người chơi khác trong bán kính
        if (player.getHealth() <= config.minHealth || isOtherPlayerNearby(client, player)) {
            enabled = false;
            resetState();
            return;
        }

        // Tạm dừng khi đang mở bất kỳ GUI hay khung chat nào
        if (client.currentScreen != null) {
            return;
        }

        // Đang trong quãng nghỉ ngắn sau một chuỗi kill hoặc vừa giết xong 1 con
        if (now < breakEndTime || now < postKillCooldownUntil) {
            applyMicroMouseDrift(player);
            return;
        }

        // Kiểm tra nếu mục tiêu vừa bị tiêu diệt -> Tạo Post-Kill Random Delay & tính nghỉ ngơi
        if (currentTarget != null && wasTargetAlive && (currentTarget.isDead() || !currentTarget.isAlive())) {
            wasTargetAlive = false;
            currentTarget = null;
            killCount++;
            postKillCooldownUntil = now + gaussianBetween(config.minPostKillDelayMs, config.maxPostKillDelayMs);
            randomizeDynamicParameters();

            if (killCount >= killsUntilNextBreak) {
                breakEndTime = now + gaussianBetween(config.minBreakMs, config.maxBreakMs);
                resetBreakCounter();
            }
            return;
        }

        // 1. TÌM MỤC TIÊU (Chỉ Wither Skeleton)
        WitherSkeletonEntity bestTarget = findTarget(client, player);
        if (bestTarget == null) {
            currentTarget = null;
            pendingSwordSlot = -1;
            return;
        }

        // Khi phát hiện Wither Skeleton mới -> Tạo Random Delay cho cả mắt nhìn và tay đánh
        if (bestTarget != currentTarget) {
            currentTarget = bestTarget;
            wasTargetAlive = currentTarget.isAlive();
            targetAcquiredTime = now;
            currentReactionDelay = gaussianBetween(config.minReactionMs, config.maxReactionMs);
            nextRotationStartTime = now + gaussianBetween(35, 125); // Không quay đầu ngay ở tick đầu tiên
            updateRandomAimOffset(now);
            randomizeDynamicParameters();
        }

        // Dịch chuyển điểm ngắm ngẫu nhiên theo thời gian để không bao giờ khóa cứng X, Y, Z
        if (now >= nextAimShiftTime) {
            updateRandomAimOffset(now);
        }

        // 2. ĐỔI KIẾM LOOTING VỚI RANDOM DELAY (Không đổi và đánh cùng lúc)
        if (config.autoSwitchSword) {
            handleDelayedSwordSwitch(player, now);
        }

        // Dừng nếu kiếm đang cầm sắp hỏng
        if (isWeaponAboutToBreak(player.getMainHandStack())) {
            return;
        }

        // 3. XOAY ĐẦU MƯỢT VỚI RANDOM PAUSE & GCD
        if (now >= nextRotationStartTime) {
            if (now >= rotationPauseUntil) {
                if (random.nextDouble() < config.rotationMicroPauseChance) {
                    rotationPauseUntil = now + gaussianBetween(30, 85); // Khựng tay cực ngắn như người thật
                } else {
                    rotateSmoothlyTowards(client, player, currentTarget);
                }
            }
        }

        // Chờ hết độ trễ phản ứng với mục tiêu mới hoặc độ trễ vừa đổi kiếm
        if (now - targetAcquiredTime < currentReactionDelay || now < postSwitchAttackLockUntil) {
            return;
        }

        // 4. TẤN CÔNG VỚI RANDOM DELAY TOÀN DIỆN
        if (now < nextAttackTime) {
            return;
        }

        // Chờ thanh cooldown vũ khí đạt ngưỡng ngẫu nhiên (96% - 100%)
        if (player.getAttackCooldownProgress(0.5f) < nextCooldownThreshold) {
            return;
        }

        if (config.preferCrits && !player.isOnGround() && player.fallDistance <= 0.0f) {
            return;
        }

        // Tâm ngắm phải nằm gần vùng hitbox mới vung kiếm
        if (!isLookingNearTarget(player, currentTarget, randomFloat(14.0f, 19.5f))) {
            return;
        }

        // Thỉnh thoảng cố tình đánh hụt/trễ nhịp chủ đích
        if (random.nextDouble() < config.intentionalMissChance) {
            player.swingHand(Hand.MAIN_HAND);
            scheduleNextAttack(now, true);
            return;
        }

        // Thực hiện Sprint-Reset với xác suất và thời gian giữ ngẫu nhiên
        if (config.sprintReset && player.isSprinting() && random.nextDouble() < config.sprintResetChance) {
            player.setSprinting(false);
            holdingSprintReset = true;
            sprintResumeTime = now + gaussianBetween(config.minSprintHoldMs, config.maxSprintHoldMs);
        }

        // Đánh duy nhất 1 mục tiêu
        client.interactionManager.attackEntity(player, currentTarget);
        player.swingHand(Hand.MAIN_HAND);

        scheduleNextAttack(now, false);
        randomizeDynamicParameters();
    }

    private void handleDelayedSwordSwitch(ClientPlayerEntity player, long now) {
        int bestSlot = findBestSwordSlot(player);
        if (bestSlot == -1 || player.getInventory().selectedSlot == bestSlot) {
            pendingSwordSlot = -1;
            return;
        }

        if (pendingSwordSlot != bestSlot) {
            pendingSwordSlot = bestSlot;
            executeSwitchTime = now + gaussianBetween(config.minSwitchDelayMs, config.maxSwitchDelayMs);
            return;
        }

        if (now >= executeSwitchTime) {
            player.getInventory().selectedSlot = pendingSwordSlot;
            pendingSwordSlot = -1;
            postSwitchAttackLockUntil = now + gaussianBetween(config.minSwitchDelayMs, config.maxSwitchDelayMs);
        }
    }

    private int findBestSwordSlot(ClientPlayerEntity player) {
        int bestSlot = -1;
        double bestScore = -1.0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() instanceof SwordItem && !isWeaponAboutToBreak(stack)) {
                int lootingLevel = getLootingLevel(stack);
                double score = (lootingLevel * 100.0) + (stack.getMaxDamage() - stack.getDamage()) * 0.001;
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private WitherSkeletonEntity findTarget(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d eyePos = player.getEyePos();
        double maxRangeSq = dynamicRange * dynamicRange;

        List<WitherSkeletonEntity> validTargets = StreamSupport.stream(client.world.getEntities().spliterator(), false)
                .filter(e -> e instanceof WitherSkeletonEntity)
                .map(e -> (WitherSkeletonEntity) e)
                .filter(Entity::isAlive)
                .filter(e -> !e.isRemoved())
                .filter(e -> getClosestPointOnBox(eyePos, e.getBoundingBox()).squaredDistanceTo(eyePos) <= maxRangeSq)
                .filter(player::canSee)
                .filter(e -> getAngleToEntity(player, e) <= dynamicFov * 0.5f)
                .toList();

        if (validTargets.isEmpty()) return null;

        if ("HEALTH".equalsIgnoreCase(config.priorityMode)) {
            return validTargets.stream()
                    .min(Comparator.comparingDouble(WitherSkeletonEntity::getHealth)
                            .thenComparingDouble(e -> e.squaredDistanceTo(player)))
                    .orElse(null);
        } else {
            return validTargets.stream()
                    .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(player)))
                    .orElse(null);
        }
    }

    private void rotateSmoothlyTowards(MinecraftClient client, ClientPlayerEntity player, WitherSkeletonEntity target) {
        Box box = target.getBoundingBox();
        Vec3d targetPoint = new Vec3d(
                box.getCenter().x + aimOffset.x,
                box.minY + box.getLengthY() * 0.66 + aimOffset.y,
                box.getCenter().z + aimOffset.z
        );

        Vec3d diff = targetPoint.subtract(player.getEyePos());
        double distXZ = Math.sqrt(diff.x * diff.x + diff.z * diff.z);

        float targetYaw = (float) (MathHelper.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) (-(MathHelper.atan2(diff.y, distXZ) * (180.0 / Math.PI)));

        float yawDelta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDelta = MathHelper.wrapDegrees(targetPitch - player.getPitch());

        // Lazy Aim: Nếu tâm ngắm đã nằm sát điểm mục tiêu thì chỉ rung cực nhẹ chứ không ép khóa tâm
        if (Math.abs(yawDelta) < 2.2f && Math.abs(pitchDelta) < 2.2f) {
            applyMicroMouseDrift(player);
            return;
        }

        float maxYawStep = randomFloat(config.minYawSpeed, config.maxYawSpeed);
        float maxPitchStep = randomFloat(config.minPitchSpeed, config.maxPitchSpeed);

        float smoothFactorYaw = randomFloat(0.42f, 0.68f);
        float smoothFactorPitch = randomFloat(0.38f, 0.62f);

        float clampedYaw = MathHelper.clamp(yawDelta * smoothFactorYaw, -maxYawStep, maxYawStep)
                + (float) (random.nextGaussian() * 0.16f);
        float clampedPitch = MathHelper.clamp(pitchDelta * smoothFactorPitch, -maxPitchStep, maxPitchStep)
                + (float) (random.nextGaussian() * 0.14f);

        float gcd = getMouseGCD(client);
        clampedYaw -= (clampedYaw % gcd);
        clampedPitch -= (clampedPitch % gcd);

        player.setYaw(player.getYaw() + clampedYaw);
        player.setPitch(MathHelper.clamp(player.getPitch() + clampedPitch, -89.9f, 89.9f));
    }

    private void scheduleNextAttack(long now, boolean wasIntentionalPause) {
        double gaussian = Math.abs(random.nextGaussian());
        long delay = Math.round(config.meanAttackDelayMs + (random.nextGaussian() * config.stdDevAttackDelayMs));
        delay = Math.max(22, delay + Math.round(gaussian * 18));

        // 10% cơ hội có nhịp trễ lớn bất thường (240ms - 780ms) phá vỡ mọi pattern
        if (wasIntentionalPause || random.nextDouble() < 0.10) {
            delay += gaussianBetween(240, 780);
        }

        nextAttackTime = now + delay;
    }

    private void randomizeDynamicParameters() {
        dynamicRange = config.minRange + random.nextDouble() * Math.max(0.05, config.maxRange - config.minRange);
        dynamicFov = randomFloat(config.minFov, config.maxFov);
        if (random.nextDouble() < 0.72) {
            nextCooldownThreshold = 1.0f;
        } else {
            nextCooldownThreshold = randomFloat(config.minCooldownThreshold, 1.0f);
        }
    }

    private void updateRandomAimOffset(long now) {
        double r = config.hitboxRandomOffset;
        aimOffset = new Vec3d(
                MathHelper.clamp(random.nextGaussian() * r * 0.55, -r, r),
                MathHelper.clamp(random.nextGaussian() * r * 0.65, -r, r),
                MathHelper.clamp(random.nextGaussian() * r * 0.55, -r, r)
        );
        nextAimShiftTime = now + gaussianBetween(config.minAimShiftMs, config.maxAimShiftMs);
    }

    private void applyMicroMouseDrift(ClientPlayerEntity player) {
        if (random.nextDouble() < 0.28) {
            float gcd = getMouseGCD(MinecraftClient.getInstance());
            float dYaw = (float) (random.nextGaussian() * 0.18f);
            float dPitch = (float) (random.nextGaussian() * 0.12f);
            dYaw -= (dYaw % gcd);
            dPitch -= (dPitch % gcd);
            player.setYaw(player.getYaw() + dYaw);
            player.setPitch(MathHelper.clamp(player.getPitch() + dPitch, -89.9f, 89.9f));
        }
    }

    private int getLootingLevel(ItemStack stack) {
        ItemEnchantmentsComponent enchantments = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        for (RegistryEntry<Enchantment> entry : enchantments.getEnchantments()) {
            if (entry.matchesKey(Enchantments.LOOTING)) {
                return enchantments.getLevel(entry);
            }
        }
        return 0;
    }

    private boolean isWeaponAboutToBreak(ItemStack stack) {
        if (!stack.isDamageable()) return false;
        return (stack.getMaxDamage() - stack.getDamage()) <= config.minWeaponDurability;
    }

    private boolean isOtherPlayerNearby(MinecraftClient client, ClientPlayerEntity self) {
        double radiusSq = config.playerPanicRadius * config.playerPanicRadius;
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other != self && other.isAlive() && !other.isSpectator()) {
                if (self.squaredDistanceTo(other) <= radiusSq) {
                    return true;
                }
            }
        }
        return false;
    }

    private float getMouseGCD(MinecraftClient client) {
        double sensitivity = client.options.getMouseSensitivity().getValue();
        double f = sensitivity * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    private Vec3d getClosestPointOnBox(Vec3d pos, Box box) {
        return new Vec3d(
                MathHelper.clamp(pos.x, box.minX, box.maxX),
                MathHelper.clamp(pos.y, box.minY, box.maxY),
                MathHelper.clamp(pos.z, box.minZ, box.maxZ)
        );
    }

    private float getAngleToEntity(ClientPlayerEntity player, Entity target) {
        Vec3d diff = target.getBoundingBox().getCenter().subtract(player.getEyePos());
        float targetYaw = (float) (MathHelper.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        return Math.abs(MathHelper.wrapDegrees(targetYaw - player.getYaw()));
    }

    private boolean isLookingNearTarget(ClientPlayerEntity player, Entity target, float maxAngleError) {
        return getAngleToEntity(player, target) <= maxAngleError;
    }

    private void resetState() {
        currentTarget = null;
        pendingSwordSlot = -1;
        holdingSprintReset = false;
    }

    private void resetBreakCounter() {
        killCount = 0;
        killsUntilNextBreak = gaussianBetween(config.minKillsBeforeBreak, config.maxKillsBeforeBreak);
    }

    /**
     * Sinh số ngẫu nhiên theo phân phối chuông Gaussian nằm trong khoảng [min, max]
     * giúp tránh hoàn toàn phân phối đều (Uniform Distribution) của máy tính
     */
    private int gaussianBetween(int min, int max) {
        if (min >= max) return min;
        double mean = (min + max) / 2.0;
        double stdDev = (max - min) / 4.0;
        int val = (int) Math.round(mean + random.nextGaussian() * stdDev);
        return MathHelper.clamp(val, min, max);
    }

    private float randomFloat(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }
            }
          
