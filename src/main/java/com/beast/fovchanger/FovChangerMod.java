package com.beast.fovchanger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * FOV Changer Plus - mod de FOV estilo Badlion para Forge 1.8.9 (client-side).
 *
 * Abrir o menu: tecla K (mude em Opcoes > Controles) ou /fov
 */
@Mod(modid = "fovchangerplus", name = "FOV Changer Plus", version = "1.0", clientSideOnly = true)
public class FovChangerMod {

    // ==================== TIPOS DE TRANSICAO ====================
    public static final int TYPE_OLD = 0;      // muda na hora
    public static final int TYPE_SMOOTH = 1;   // transicao suave (por frame)
    public static final int TYPE_VANILLA = 2;  // transicao igual ao vanilla (por tick)
    private static final String[] TYPE_NAMES = { "Old", "Smooth", "Vanilla" };

    // ==================== CONFIGURACOES ====================
    public static boolean enabled = true;
    public static int normalFov = 70;
    public static int sprintFov = 80;
    public static int speed1Fov = 77;
    public static int speed2Fov = 84;
    public static int slowFov = 65;
    public static boolean dynamicSprint = true;
    public static boolean dynamicBow = true;
    public static boolean dynamicEffect = true;
    public static int transition = TYPE_SMOOTH;
    public static int smoothSpeed = 8;

    // chaves dos sliders
    private static final int K_NORMAL = 0;
    private static final int K_SPRINT = 1;
    private static final int K_SPEED1 = 2;
    private static final int K_SPEED2 = 3;
    private static final int K_SLOW = 4;
    private static final int K_SMOOTH = 5;

    static int get(int key) {
        switch (key) {
            case K_NORMAL: return normalFov;
            case K_SPRINT: return sprintFov;
            case K_SPEED1: return speed1Fov;
            case K_SPEED2: return speed2Fov;
            case K_SLOW: return slowFov;
            default: return smoothSpeed;
        }
    }

    static void set(int key, int v) {
        switch (key) {
            case K_NORMAL: normalFov = v; break;
            case K_SPRINT: sprintFov = v; break;
            case K_SPEED1: speed1Fov = v; break;
            case K_SPEED2: speed2Fov = v; break;
            case K_SLOW: slowFov = v; break;
            default: smoothSpeed = v; break;
        }
    }

    static int min(int key) { return key == K_SMOOTH ? 1 : 30; }
    static int max(int key) { return key == K_SMOOTH ? 30 : 110; }

    // Reset de um slider: volta pro FOV base (Normal). O Normal volta pra 70.
    static void resetOne(int key) {
        if (key == K_NORMAL) normalFov = 70;
        else if (key == K_SMOOTH) smoothSpeed = 8;
        else set(key, normalFov);
    }

    static void resetAll() {
        normalFov = 70;
        sprintFov = 80;
        speed1Fov = 77;
        speed2Fov = 84;
        slowFov = 65;
        dynamicSprint = true;
        dynamicBow = true;
        dynamicEffect = true;
        transition = TYPE_SMOOTH;
        smoothSpeed = 8;
    }

    // ==================== ESTADO INTERNO ====================
    private final Minecraft mc = Minecraft.getMinecraft();
    public static KeyBinding openGuiKey;
    private static boolean openGuiNextTick = false;

    private float tickFov = -1.0F;      // usado no modo Vanilla
    private float prevTickFov = -1.0F;
    private float smoothFov = -1.0F;    // usado no modo Smooth
    private long lastNs = 0L;

    private float savedFov = 70.0F;
    private boolean overridden = false;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new FovCommand());

        openGuiKey = new KeyBinding("FOV Changer Menu", Keyboard.KEY_K, "key.categories.misc");
        ClientRegistry.registerKeyBinding(openGuiKey);

        initConfig();
        loadSettings();
    }

    // ==================== CALCULO DO FOV ALVO ====================
    private float computeTarget() {
        EntityPlayerSP p = mc.thePlayer;
        float n = normalFov;
        float t = n;

        // Speed / Slowness
        if (dynamicEffect) {
            PotionEffect speed = p.getActivePotionEffect(Potion.moveSpeed);
            PotionEffect slow = p.getActivePotionEffect(Potion.moveSlowdown);
            if (speed != null) {
                t = speed.getAmplifier() >= 1 ? speed2Fov : speed1Fov;
            } else if (slow != null) {
                t = slowFov;
            }
        }

        // Sprint (soma a diferenca entre o FOV de sprint e o normal)
        if (dynamicSprint && p.isSprinting()) {
            t += (sprintFov - n);
        }

        // Arco (zoom igual ao vanilla: ate -15%)
        if (dynamicBow && p.isUsingItem()) {
            ItemStack inUse = p.getItemInUse();
            if (inUse != null && inUse.getItem() == Items.bow) {
                float f = p.getItemInUseDuration() / 20.0F;
                f = f > 1.0F ? 1.0F : f * f;
                t *= 1.0F - f * 0.15F;
            }
        }
        return t;
    }

    private void resetState() {
        tickFov = -1.0F;
        prevTickFov = -1.0F;
        smoothFov = -1.0F;
        lastNs = 0L;
    }

    // ==================== TICKS ====================
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // abrir o menu (o comando so pode abrir 1 tick depois, senao o chat fecha ele)
        while (openGuiKey.isPressed()) {
            openGuiNextTick = true;
        }
        if (openGuiNextTick) {
            openGuiNextTick = false;
            mc.displayGuiScreen(new FovScreen(null));
        }

        if (mc.thePlayer == null || mc.theWorld == null) {
            resetState();
            return;
        }

        // modo Vanilla: lerp de 0.5 por tick, igual ao jogo original
        float target = computeTarget();
        if (tickFov < 0.0F) {
            tickFov = target;
            prevTickFov = target;
        }
        prevTickFov = tickFov;
        tickFov += (target - tickFov) * 0.5F;
    }

    // Antes de cada frame: coloca o FOV calculado no lugar do FOV do jogo.
    // Depois do frame: devolve o valor original (assim o options.txt nao e alterado).
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            if (!enabled || mc.thePlayer == null || mc.theWorld == null) {
                smoothFov = -1.0F;
                lastNs = 0L;
                return;
            }

            float target = computeTarget();

            long now = System.nanoTime();
            float dt = lastNs == 0L ? 0.0F : (now - lastNs) / 1.0E9F;
            lastNs = now;
            if (dt > 0.1F) dt = 0.1F;

            float value;
            if (transition == TYPE_OLD) {
                value = target;
            } else if (transition == TYPE_SMOOTH) {
                if (smoothFov < 0.0F) smoothFov = target;
                value = smoothFov + (target - smoothFov) * (1.0F - (float) Math.exp(-smoothSpeed * dt));
            } else {
                if (tickFov < 0.0F) {
                    tickFov = target;
                    prevTickFov = target;
                }
                value = prevTickFov + (tickFov - prevTickFov) * event.renderTickTime;
            }
            smoothFov = value;

            if (value < 10.0F) value = 10.0F;
            if (value > 170.0F) value = 170.0F;

            savedFov = mc.gameSettings.fovSetting;
            mc.gameSettings.fovSetting = value;
            overridden = true;
        } else if (overridden) {
            mc.gameSettings.fovSetting = savedFov;
            overridden = false;
        }
    }

    // Anula o efeito de FOV do vanilla (sprint/speed/arco), quem controla agora e o mod.
    @SubscribeEvent
    public void onFovUpdate(FOVUpdateEvent event) {
        if (enabled && event.entity == mc.thePlayer) {
            event.newfov = 1.0F;
        }
    }

    // ==================== CONFIG ====================
    private static File configFile;

    private static void initConfig() {
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "fovchanger");
        if (!dir.exists()) dir.mkdirs();
        configFile = new File(dir, "settings.cfg");
    }

    private static int clampInt(String value, int min, int max, int def) {
        try {
            int v = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void loadSettings() {
        if (configFile == null || !configFile.exists()) {
            saveSettings();
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;
                String key = parts[0].trim();
                String value = parts[1].trim();
                switch (key) {
                    case "enabled": enabled = Boolean.parseBoolean(value); break;
                    case "normalFov": normalFov = clampInt(value, 30, 110, 70); break;
                    case "sprintFov": sprintFov = clampInt(value, 30, 110, 80); break;
                    case "speed1Fov": speed1Fov = clampInt(value, 30, 110, 77); break;
                    case "speed2Fov": speed2Fov = clampInt(value, 30, 110, 84); break;
                    case "slowFov": slowFov = clampInt(value, 30, 110, 65); break;
                    case "dynamicSprint": dynamicSprint = Boolean.parseBoolean(value); break;
                    case "dynamicBow": dynamicBow = Boolean.parseBoolean(value); break;
                    case "dynamicEffect": dynamicEffect = Boolean.parseBoolean(value); break;
                    case "transition": transition = clampInt(value, 0, 2, TYPE_SMOOTH); break;
                    case "smoothSpeed": smoothSpeed = clampInt(value, 1, 30, 8); break;
                }
            }
        } catch (Exception e) {
            System.err.println("[FOVChanger] Load failed: " + e.getMessage());
        }
    }

    public static void saveSettings() {
        if (configFile == null) {
            try {
                initConfig();
            } catch (Exception e) {
                return;
            }
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile))) {
            writer.write("# FOV Changer Plus Config\n");
            writer.write("enabled=" + enabled + "\n");
            writer.write("normalFov=" + normalFov + "\n");
            writer.write("sprintFov=" + sprintFov + "\n");
            writer.write("speed1Fov=" + speed1Fov + "\n");
            writer.write("speed2Fov=" + speed2Fov + "\n");
            writer.write("slowFov=" + slowFov + "\n");
            writer.write("dynamicSprint=" + dynamicSprint + "\n");
            writer.write("dynamicBow=" + dynamicBow + "\n");
            writer.write("dynamicEffect=" + dynamicEffect + "\n");
            writer.write("transition=" + transition + "\n");
            writer.write("smoothSpeed=" + smoothSpeed + "\n");
            writer.flush();
        } catch (Exception e) {
            // ignora
        }
    }

    // ==================== COMANDO ====================
    public static class FovCommand extends CommandBase {
        @Override public String getCommandName() { return "fov"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/fov [toggle|reset]"; }
        @Override public List<String> getCommandAliases() { return Arrays.asList("fovchanger"); }
        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                openGuiNextTick = true;
                return;
            }
            String cmd = args[0].toLowerCase();
            if ("toggle".equals(cmd)) {
                enabled = !enabled;
                sender.addChatMessage(new ChatComponentText("[FOV Changer] " + (enabled ? "ON" : "OFF")));
                saveSettings();
            } else if ("reset".equals(cmd)) {
                resetAll();
                sender.addChatMessage(new ChatComponentText("[FOV Changer] Settings reset"));
                saveSettings();
            } else {
                sender.addChatMessage(new ChatComponentText("[FOV Changer] /fov, /fov toggle, /fov reset"));
            }
        }
    }

    // ==================== SLIDER ====================
    public static class FovSlider extends GuiButton {
        private final int key;
        private final int minV;
        private final int maxV;
        private float value;
        private boolean dragging = false;

        private static final String[] LABELS = {
            "Normal FOV", "Sprinting FOV", "Speed I FOV", "Speed II FOV", "Slowness FOV", "Smooth Speed"
        };

        public FovSlider(int id, int x, int y, int w, int key) {
            super(id, x, y, w, 20, "");
            this.key = key;
            this.minV = min(key);
            this.maxV = max(key);
            sync();
        }

        public void sync() {
            int v = get(key);
            this.value = (v - minV) / (float) (maxV - minV);
            this.displayString = text(v);
        }

        private String text(int v) {
            String extra = "";
            if (key != K_SMOOTH) {
                if (v == 70) extra = " (Normal)";
                else if (v == 110) extra = " (Quake Pro)";
            }
            return LABELS[key] + ": " + v + extra;
        }

        private void updateFromMouse(int mouseX) {
            float f = (float) (mouseX - (this.xPosition + 4)) / (float) (this.width - 8);
            if (f < 0.0F) f = 0.0F;
            if (f > 1.0F) f = 1.0F;
            int v = Math.round(minV + f * (maxV - minV));
            set(key, v);
            sync();
        }

        @Override
        protected int getHoverState(boolean mouseOver) {
            return 0;
        }

        @Override
        protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
            if (!this.visible) return;
            if (this.dragging) {
                updateFromMouse(mouseX);
            }
            mc.getTextureManager().bindTexture(buttonTextures);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int knobX = this.xPosition + (int) (this.value * (float) (this.width - 8));
            this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
            this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (super.mousePressed(mc, mouseX, mouseY)) {
                updateFromMouse(mouseX);
                this.dragging = true;
                return true;
            }
            return false;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            if (this.dragging) {
                this.dragging = false;
                saveSettings();
            }
        }
    }

    // ==================== MENU ====================
    public static class FovScreen extends GuiScreen {
        private final GuiScreen parent;
        private final FovSlider[] sliders = new FovSlider[6];
        private GuiButton btnEnabled;
        private GuiButton btnSprint;
        private GuiButton btnBow;
        private GuiButton btnEffect;
        private GuiButton btnTransition;

        public FovScreen(GuiScreen parent) {
            this.parent = parent;
        }

        private static String onOff(boolean b) {
            return b ? "\u00a7aON" : "\u00a7cOFF";
        }

        @Override
        public void initGui() {
            int leftX = this.width / 2 - 154;
            int rightX = this.width / 2 + 4;
            int y0 = 36;
            int step = 24;

            // coluna da esquerda: sliders de FOV + botao de reset (R)
            for (int i = 0; i <= K_SLOW; i++) {
                sliders[i] = new FovSlider(200 + i, leftX, y0 + i * step, 130, i);
                this.buttonList.add(sliders[i]);
                this.buttonList.add(new GuiButton(100 + i, leftX + 134, y0 + i * step, 20, 20, "R"));
            }

            // coluna da direita: opcoes
            btnEnabled = new GuiButton(10, rightX, y0, 150, 20, "");
            btnSprint = new GuiButton(11, rightX, y0 + step, 150, 20, "");
            btnBow = new GuiButton(12, rightX, y0 + step * 2, 150, 20, "");
            btnEffect = new GuiButton(13, rightX, y0 + step * 3, 150, 20, "");
            btnTransition = new GuiButton(14, rightX, y0 + step * 4, 150, 20, "");
            this.buttonList.add(btnEnabled);
            this.buttonList.add(btnSprint);
            this.buttonList.add(btnBow);
            this.buttonList.add(btnEffect);
            this.buttonList.add(btnTransition);

            sliders[K_SMOOTH] = new FovSlider(200 + K_SMOOTH, rightX, y0 + step * 5, 130, K_SMOOTH);
            this.buttonList.add(sliders[K_SMOOTH]);
            this.buttonList.add(new GuiButton(100 + K_SMOOTH, rightX + 134, y0 + step * 5, 20, 20, "R"));

            // rodape
            this.buttonList.add(new GuiButton(20, leftX, this.height - 28, 150, 20, "Reset All"));
            this.buttonList.add(new GuiButton(21, rightX, this.height - 28, 150, 20, "Done"));

            refresh();
        }

        private void refresh() {
            btnEnabled.displayString = "FOV Changer: " + onOff(enabled);
            btnSprint.displayString = "Dynamic Sprint: " + onOff(dynamicSprint);
            btnBow.displayString = "Dynamic Bow: " + onOff(dynamicBow);
            btnEffect.displayString = "Dynamic Status Effect: " + onOff(dynamicEffect);
            btnTransition.displayString = "Transition: " + TYPE_NAMES[transition];
            for (FovSlider s : sliders) {
                if (s != null) s.sync();
            }
            sliders[K_SMOOTH].enabled = (transition == TYPE_SMOOTH);
        }

        @Override
        protected void actionPerformed(GuiButton b) throws IOException {
            if (b.id == 10) enabled = !enabled;
            else if (b.id == 11) dynamicSprint = !dynamicSprint;
            else if (b.id == 12) dynamicBow = !dynamicBow;
            else if (b.id == 13) dynamicEffect = !dynamicEffect;
            else if (b.id == 14) transition = (transition + 1) % 3;
            else if (b.id >= 100 && b.id <= 105) resetOne(b.id - 100);
            else if (b.id == 20) resetAll();
            else if (b.id == 21) {
                saveSettings();
                this.mc.displayGuiScreen(parent);
                return;
            }
            refresh();
            saveSettings();
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            this.drawDefaultBackground();
            this.drawCenteredString(this.fontRendererObj, "FOV Changer", this.width / 2, 14, 0xFFFFFF);
            this.drawCenteredString(this.fontRendererObj, "R = reset to base FOV", this.width / 2, this.height - 42, 0xAAAAAA);
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        public void onGuiClosed() {
            saveSettings();
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }
}
