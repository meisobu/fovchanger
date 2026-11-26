package com.beast.fovchanger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ChatAllowedCharacters;
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
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FovChanger - mod de FOV estilo Badlion para Forge 1.8.9 (client-side).
 * Created by mei.
 * Menu no estilo Essential.
 *
 * Abrir o menu: tecla K (mude em Opcoes > Controles) ou /fov
 */
@Mod(modid = "fovchanger", name = "FovChanger", version = "1.0", clientSideOnly = true)
public class FovChangerMod {

    // ==================== TIPOS DE TRANSICAO ====================
    public static final int TYPE_OLD = 0;      // muda na hora
    public static final int TYPE_SMOOTH = 1;   // transicao suave (por frame)
    public static final int TYPE_VANILLA = 2;  // transicao igual ao vanilla (por tick)
    private static final String[] TYPE_NAMES = { "Old", "Smooth", "Vanilla" };

    // Vanilla aproxima 50% por tick (20 tps): k = -ln(0.5) * 20 ~= 13.86 por segundo.
    // Por isso o padrao do Smooth e 14 (igual ao ritmo do jogo original).
    public static final int DEFAULT_SMOOTH = 14;

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
    public static int transition = TYPE_VANILLA;
    public static int smoothSpeed = DEFAULT_SMOOTH;

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
    static int max(int key) { return key == K_SMOOTH ? 40 : 110; }

    // Reset de um slider: volta pro FOV base (Normal). O Normal volta pra 70.
    static void resetOne(int key) {
        if (key == K_NORMAL) normalFov = 70;
        else if (key == K_SMOOTH) smoothSpeed = DEFAULT_SMOOTH;
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
        transition = TYPE_VANILLA;
        smoothSpeed = DEFAULT_SMOOTH;
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

        openGuiKey = new KeyBinding("FovChanger Menu", Keyboard.KEY_K, "key.categories.misc");
        ClientRegistry.registerKeyBinding(openGuiKey);

        initConfig();
        loadSettings();
    }

    // ==================== CALCULO DO FOV ALVO ====================
    // FOV "base": efeitos + sprint (sem o arco, que e aplicado depois da suavizacao).
    private float computeBase() {
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

        // Sprint: MULTIPLICA (igual ao vanilla), nao soma.
        if (dynamicSprint && p.isSprinting()) {
            t *= (float) sprintFov / n;
        }
        return t;
    }

    // Zoom do arco igual ao vanilla (ate -15%). Aplicado direto, sem atraso.
    private float bowFactor(float partial) {
        EntityPlayerSP p = mc.thePlayer;
        if (dynamicBow && p.isUsingItem()) {
            ItemStack inUse = p.getItemInUse();
            if (inUse != null && inUse.getItem() == Items.bow) {
                float f = (p.getItemInUseDuration() + partial) / 20.0F;
                f = f > 1.0F ? 1.0F : f * f;
                return 1.0F - f * 0.15F;
            }
        }
        return 1.0F;
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

        if (!enabled || mc.thePlayer == null || mc.theWorld == null) {
            resetState();
            return;
        }

        // modo Vanilla: lerp de 0.5 por tick, igual ao jogo original
        float target = computeBase();
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

            float target = computeBase();

            long now = System.nanoTime();
            float dt = lastNs == 0L ? 0.0F : (now - lastNs) / 1.0E9F;
            lastNs = now;
            if (dt > 0.1F) dt = 0.1F;

            float base;
            if (transition == TYPE_OLD) {
                base = target;
            } else if (transition == TYPE_SMOOTH) {
                if (smoothFov < 0.0F) smoothFov = target;
                base = smoothFov + (target - smoothFov) * (1.0F - (float) Math.exp(-smoothSpeed * dt));
            } else {
                if (tickFov < 0.0F) {
                    tickFov = target;
                    prevTickFov = target;
                }
                base = prevTickFov + (tickFov - prevTickFov) * event.renderTickTime;
            }
            smoothFov = base; // mantem os modos continuos ao trocar

            float value = base * bowFactor(event.renderTickTime);
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
                    case "transition": transition = clampInt(value, 0, 2, TYPE_VANILLA); break;
                    case "smoothSpeed": smoothSpeed = clampInt(value, 1, 40, DEFAULT_SMOOTH); break;
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
            writer.write("# FovChanger Config (created by mei)\n");
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
                sender.addChatMessage(new ChatComponentText("[FovChanger] " + (enabled ? "ON" : "OFF")));
                saveSettings();
            } else if ("reset".equals(cmd)) {
                resetAll();
                sender.addChatMessage(new ChatComponentText("[FovChanger] Settings reset"));
                saveSettings();
            } else {
                sender.addChatMessage(new ChatComponentText("[FovChanger] /fov, /fov toggle, /fov reset"));
            }
        }
    }

    // ==================== MENU (estilo Essential) ====================
    public static class FovScreen extends GuiScreen {
        private static final int SIDEW = 130;
        private static final int HEADH = 30;
        private static final int SLW = 110;   // largura do slider
        private static final int SEGW = 112;  // largura do seletor de transicao
        private static final int CTRLW = 130; // espaco reservado pro controle (direita do card)

        private static final int BLUE = 0xFF0A84FF;
        private static final int BG = 0xFF181818;
        private static final int HEAD = 0xFF212121;
        private static final int LINE = 0xFF2E2E2E;
        private static final int CARD = 0xFF222222;
        private static final int CARD_BORDER = 0xFF3C3C3C;
        private static final int CARD_HOVER = 0xFF5A5A5A;
        private static final int TXT_DIM = 0xFF8A8A8A;
        private static final int TXT_OFF = 0xFF555555;

        private static final String[] PAGES = { "General", "Dynamic FOV", "Transition" };

        private static final int T_TOGGLE = 0, T_SLIDER = 1, T_SEG = 2, T_BUTTON = 3;

        private static class Entry {
            final int page, type, key;
            final String section, title, desc;
            Entry(int page, String section, int type, String title, String desc, int key) {
                this.page = page; this.section = section; this.type = type;
                this.title = title; this.desc = desc; this.key = key;
            }
        }

        private static class Row {
            Entry e;        // null = cabecalho de secao
            String header;
            int y, h;
        }

        private static final List<Entry> ENTRIES = new ArrayList<Entry>();

        private static void add(int page, String section, int type, String title, String desc, int key) {
            ENTRIES.add(new Entry(page, section, type, title, desc, key));
        }

        static {
            // key dos toggles: 0 = enabled, 1 = sprint, 2 = bow, 3 = effect
            add(0, "FovChanger", T_TOGGLE, "FovChanger", "Turns the custom FOV on or off. When off, Minecraft's own FOV is used.", 0);
            add(0, "FovChanger", T_SLIDER, "Normal FOV", "Your base field of view while standing still.", K_NORMAL);
            add(0, "Other", T_BUTTON, "Reset all settings", "Restores every FovChanger option to its default value.", 0);

            add(1, "Sprint", T_TOGGLE, "Dynamic Sprint", "Changes your FOV while you are sprinting.", 1);
            add(1, "Sprint", T_SLIDER, "Sprinting FOV", "The FOV used while sprinting.", K_SPRINT);
            add(1, "Status Effects", T_TOGGLE, "Dynamic Status Effect", "Changes your FOV with Speed and Slowness effects.", 3);
            add(1, "Status Effects", T_SLIDER, "Speed I FOV", "The FOV used with Speed I.", K_SPEED1);
            add(1, "Status Effects", T_SLIDER, "Speed II FOV", "The FOV used with Speed II.", K_SPEED2);
            add(1, "Status Effects", T_SLIDER, "Slowness FOV", "The FOV used with Slowness.", K_SLOW);
            add(1, "Bow", T_TOGGLE, "Dynamic Bow", "Zooms in while you draw back a bow.", 2);

            add(2, "Transition", T_SEG, "Transition Type", "Old changes instantly, Smooth fades, Vanilla matches the game.", 0);
            add(2, "Transition", T_SLIDER, "Smooth Speed", "How fast Smooth reaches the new FOV. 14 matches Vanilla.", K_SMOOTH);
        }

        private final GuiScreen parent;
        private int x0, y0, winW, winH;
        private int page = 0;
        private int scroll = 0;
        private int totalH = 0;
        private String search = "";
        private boolean searchFocused = false;
        private int dragging = -1;
        private int dragSx = 0;
        private final float[] anim = new float[4];
        private long lastMs = 0L;
        private final List<Row> rows = new ArrayList<Row>();

        public FovScreen(GuiScreen parent) {
            this.parent = parent;
        }

        // ---------- helpers ----------
        private static boolean in(int mx, int my, int x, int y, int w, int h) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        private static void border(int x, int y, int w, int h, int c) {
            Gui.drawRect(x, y, x + w, y + 1, c);
            Gui.drawRect(x, y + h - 1, x + w, y + h, c);
            Gui.drawRect(x, y, x + 1, y + h, c);
            Gui.drawRect(x + w - 1, y, x + w, y + h, c);
        }

        private static void box(int x, int y, int w, int h, int fill, int bord) {
            Gui.drawRect(x, y, x + w, y + h, fill);
            border(x, y, w, h, bord);
        }

        private static int lerpColor(int a, int b, float t) {
            int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
        }

        private static boolean state(int key) {
            switch (key) {
                case 0: return enabled;
                case 1: return dynamicSprint;
                case 2: return dynamicBow;
                default: return dynamicEffect;
            }
        }

        private static void flip(int key) {
            switch (key) {
                case 0: enabled = !enabled; break;
                case 1: dynamicSprint = !dynamicSprint; break;
                case 2: dynamicBow = !dynamicBow; break;
                default: dynamicEffect = !dynamicEffect; break;
            }
        }

        private int cardX() { return x0 + SIDEW + 14; }
        private int cardW() { return winW - SIDEW - 28; }
        private int contentTop() { return y0 + HEADH + 8; }

        private boolean sliderActive(int key) {
            return key != K_SMOOTH || transition == TYPE_SMOOTH;
        }

        private int cardHeight(Entry e) {
            int textW = cardW() - 20 - CTRLW;
            int lines = this.fontRendererObj.listFormattedStringToWidth(e.desc, textW).size();
            return Math.max(44, 33 + lines * 10);
        }

        private void layout() {
            rows.clear();
            String q = search.toLowerCase().trim();
            String lastSection = null;
            int y = 0;
            for (Entry e : ENTRIES) {
                if (q.isEmpty()) {
                    if (e.page != page) continue;
                } else if (!(e.title.toLowerCase().contains(q) || e.desc.toLowerCase().contains(q))) {
                    continue;
                }
                if (!e.section.equals(lastSection)) {
                    Row h = new Row();
                    h.header = e.section;
                    h.y = y;
                    h.h = 24;
                    rows.add(h);
                    y += 24;
                    lastSection = e.section;
                }
                Row r = new Row();
                r.e = e;
                r.y = y;
                r.h = cardHeight(e);
                rows.add(r);
                y += r.h + 8;
            }
            totalH = y;
        }

        private int maxScroll() {
            return Math.max(0, totalH + 4 - (winH - HEADH));
        }

        private int valueAt(int key, int mouseX, int sx) {
            float f = (float) (mouseX - sx) / (float) SLW;
            if (f < 0.0F) f = 0.0F;
            if (f > 1.0F) f = 1.0F;
            return Math.round(min(key) + f * (max(key) - min(key)));
        }

        private String sliderText(int key, int v) {
            String s = String.valueOf(v);
            if (key == K_SMOOTH) {
                if (v == DEFAULT_SMOOTH) s += " (Vanilla)";
            } else {
                if (v == 70) s += " (Normal)";
                else if (v == 110) s += " (Quake Pro)";
            }
            return s;
        }

        // ---------- ciclo de vida ----------
        @Override
        public void initGui() {
            winW = Math.min(480, this.width - 60);
            winH = Math.min(270, this.height - 30);
            x0 = (this.width - winW) / 2;
            y0 = (this.height - winH) / 2;
            Keyboard.enableRepeatEvents(true);
            for (int i = 0; i < 4; i++) anim[i] = state(i) ? 1.0F : 0.0F;
            lastMs = 0L;
        }

        @Override
        public void onGuiClosed() {
            Keyboard.enableRepeatEvents(false);
            saveSettings();
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        // ---------- desenho ----------
        @Override
        public void drawScreen(int mx, int my, float partialTicks) {
            long now = Minecraft.getSystemTime();
            float dt = lastMs == 0L ? 0.0F : (now - lastMs) / 1000.0F;
            lastMs = now;
            for (int i = 0; i < 4; i++) {
                anim[i] += ((state(i) ? 1.0F : 0.0F) - anim[i]) * Math.min(1.0F, dt * 16.0F);
            }

            layout();
            int viewH = winH - HEADH;
            int ms = maxScroll();
            if (scroll > ms) scroll = ms;
            if (scroll < 0) scroll = 0;

            if (dragging >= 0) {
                if (Mouse.isButtonDown(0)) {
                    set(dragging, valueAt(dragging, mx, dragSx));
                } else {
                    dragging = -1;
                    saveSettings();
                }
            }

            Gui.drawRect(0, 0, this.width, this.height, 0x99000000);

            // botao voltar (fora da janela)
            int bx = x0 - 28, by = y0;
            boolean bh = in(mx, my, bx, by, 22, 22);
            box(bx, by, 22, 22, bh ? 0xFF333333 : 0xFF262626, CARD_BORDER);
            this.drawCenteredString(this.fontRendererObj, "<", bx + 11, by + 7, 0xFFFFFFFF);

            // janela
            Gui.drawRect(x0 - 2, y0 - 2, x0 + winW + 2, y0 + winH + 2, 0xFF2A2A2A);
            Gui.drawRect(x0, y0, x0 + winW, y0 + winH, BG);

            // barra lateral
            Gui.drawRect(x0, y0, x0 + SIDEW, y0 + HEADH, HEAD);
            this.fontRendererObj.drawStringWithShadow("FovChanger", x0 + 12, y0 + 11, 0xFFFFFFFF);

            for (int i = 0; i < PAGES.length; i++) {
                int py = y0 + HEADH + 10 + i * 16;
                boolean hover = in(mx, my, x0, py - 3, SIDEW - 3, 16);
                int col = (search.isEmpty() && i == page) ? BLUE : (hover ? 0xFFFFFFFF : 0xFFC8C8C8);
                this.fontRendererObj.drawStringWithShadow(PAGES[i], x0 + 12, py, col);
            }

            Gui.drawRect(x0, y0 + winH - 56, x0 + SIDEW - 3, y0 + winH - 55, LINE);
            this.fontRendererObj.drawString("FovChanger v1.0", x0 + 12, y0 + winH - 48, TXT_DIM);
            this.fontRendererObj.drawString("Created by mei", x0 + 12, y0 + winH - 38, TXT_DIM);
            this.fontRendererObj.drawString("MC 1.8.9 [Forge]", x0 + 12, y0 + winH - 28, TXT_DIM);

            Gui.drawRect(x0 + SIDEW - 3, y0, x0 + SIDEW, y0 + winH, 0xFF2C2C2C);

            // cabecalho do conteudo + busca
            Gui.drawRect(x0 + SIDEW, y0, x0 + winW, y0 + HEADH, HEAD);
            int sbx = x0 + SIDEW + 14, sby = y0 + 7;
            box(sbx, sby, 150, 16, 0xFF2C2C2C, searchFocused ? BLUE : 0xFF3A3A3A);
            if (search.isEmpty() && !searchFocused) {
                this.fontRendererObj.drawString("Search", sbx + 6, sby + 4, TXT_DIM);
            } else {
                String shown = search + (searchFocused && (System.currentTimeMillis() / 500L) % 2L == 0L ? "_" : "");
                this.fontRendererObj.drawString(shown, sbx + 6, sby + 4, 0xFFFFFFFF);
            }

            // conteudo com scroll (recortado)
            ScaledResolution sr = new ScaledResolution(this.mc);
            int f = sr.getScaleFactor();
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor((x0 + SIDEW) * f, (this.height - (y0 + winH)) * f, (winW - SIDEW) * f, viewH * f);
            for (Row r : rows) {
                int ry = contentTop() + r.y - scroll;
                if (ry + r.h < y0 + HEADH || ry > y0 + winH) continue;
                if (r.e == null) drawHeader(r, ry);
                else drawCard(r, ry, mx, my);
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);

            // barra de rolagem
            if (ms > 0) {
                Gui.drawRect(x0 + winW - 6, y0 + HEADH, x0 + winW - 3, y0 + winH, 0xFF202020);
                int thumbH = Math.max(16, viewH * viewH / (totalH + 4));
                int thumbY = y0 + HEADH + (viewH - thumbH) * scroll / ms;
                Gui.drawRect(x0 + winW - 6, thumbY, x0 + winW - 3, thumbY + thumbH, 0xFF555555);
            }

            if (rows.isEmpty()) {
                this.drawCenteredString(this.fontRendererObj, "No results", x0 + SIDEW + (winW - SIDEW) / 2, y0 + HEADH + 30, TXT_DIM);
            }
        }

        private void drawHeader(Row r, int ry) {
            int cx = cardX(), cw = cardW();
            int mid = ry + 8;
            int tw = this.fontRendererObj.getStringWidth(r.header);
            int center = cx + cw / 2;
            Gui.drawRect(cx, mid, center - tw / 2 - 8, mid + 1, 0xFF3A3A3A);
            Gui.drawRect(center + tw / 2 + 8, mid, cx + cw, mid + 1, 0xFF3A3A3A);
            this.fontRendererObj.drawStringWithShadow(r.header, center - tw / 2, mid - 4, 0xFFE0E0E0);
        }

        private void drawCard(Row r, int ry, int mx, int my) {
            Entry e = r.e;
            int cx = cardX(), cw = cardW();
            boolean clip = my >= y0 + HEADH && my < y0 + winH && mx >= x0 + SIDEW && mx < x0 + winW;
            boolean hover = clip && in(mx, my, cx, ry, cw, r.h);
            box(cx, ry, cw, r.h, CARD, hover ? CARD_HOVER : CARD_BORDER);

            boolean active = e.type != T_SLIDER || sliderActive(e.key);
            this.fontRendererObj.drawStringWithShadow("\u00a7l" + e.title, cx + 10, ry + 10, active ? 0xFFFFFFFF : TXT_OFF);

            List<String> lines = this.fontRendererObj.listFormattedStringToWidth(e.desc, cw - 20 - CTRLW);
            for (int i = 0; i < lines.size(); i++) {
                this.fontRendererObj.drawString(lines.get(i), cx + 10, ry + 23 + i * 10, active ? TXT_DIM : TXT_OFF);
            }

            int cy = ry + r.h / 2;
            int right = cx + cw - 10;

            if (e.type == T_TOGGLE) {
                drawToggle(right - 40, cy - 9, anim[e.key]);
            } else if (e.type == T_SLIDER) {
                drawSlider(e.key, right - SLW, cy, mx, my, active);
            } else if (e.type == T_SEG) {
                int sx = right - SEGW;
                for (int i = 0; i < 3; i++) {
                    int bx = sx + i * 38;
                    boolean sel = transition == i;
                    boolean bh = clip && in(mx, my, bx, cy - 8, 36, 16);
                    if (sel) box(bx, cy - 8, 36, 16, BLUE, BLUE);
                    else box(bx, cy - 8, 36, 16, bh ? 0xFF383838 : 0xFF2C2C2C, 0xFF555555);
                    int tw = this.fontRendererObj.getStringWidth(TYPE_NAMES[i]);
                    this.fontRendererObj.drawString(TYPE_NAMES[i], bx + (36 - tw) / 2, cy - 4, sel ? 0xFFFFFFFF : 0xFFBBBBBB);
                }
            } else {
                int bx = right - 60;
                boolean bh = clip && in(mx, my, bx, cy - 8, 60, 16);
                box(bx, cy - 8, 60, 16, bh ? 0xFF383838 : 0xFF2C2C2C, bh ? BLUE : 0xFF555555);
                int tw = this.fontRendererObj.getStringWidth("Reset");
                this.fontRendererObj.drawString("Reset", bx + (60 - tw) / 2, cy - 4, 0xFFFFFFFF);
            }
        }

        // toggle quadrado igual ao do Essential
        private void drawToggle(int x, int y, float a) {
            int bord = lerpColor(0xFF6E6E6E, BLUE, a);
            Gui.drawRect(x, y, x + 40, y + 18, bord);
            Gui.drawRect(x + 1, y + 1, x + 39, y + 17, 0xFF1B1B1B);
            int kx = x + 2 + Math.round(a * 18.0F);
            int kc = lerpColor(0xFF8A8A8A, BLUE, a);
            Gui.drawRect(kx, y + 2, kx + 18, y + 16, kc);
            String t = a > 0.5F ? "I" : "0";
            int tw = this.fontRendererObj.getStringWidth(t);
            this.fontRendererObj.drawString(t, kx + (18 - tw) / 2, y + 5, a > 0.5F ? 0xFFFFFFFF : 0xFF1B1B1B);
        }

        private void drawSlider(int key, int sx, int cy, int mx, int my, boolean active) {
            int v = get(key);
            float f = (v - min(key)) / (float) (max(key) - min(key));

            String vs = sliderText(key, v);
            int vw = this.fontRendererObj.getStringWidth(vs);
            this.fontRendererObj.drawString(vs, sx + SLW - vw, cy - 12, active ? 0xFFDDDDDD : TXT_OFF);

            int ty = cy + 3;
            Gui.drawRect(sx, ty, sx + SLW, ty + 2, 0xFF3A3A3A);
            int fillW = (int) (f * SLW);
            Gui.drawRect(sx, ty, sx + fillW, ty + 2, active ? BLUE : 0xFF444444);

            int kx = sx + fillW;
            boolean hover = active && (dragging == key || in(mx, my, sx - 4, cy - 14, SLW + 8, 28));
            Gui.drawRect(kx - 2, ty - 4, kx + 2, ty + 6, active ? (hover ? 0xFFFFFFFF : 0xFFDDDDDD) : 0xFF666666);
        }

        // ---------- input ----------
        @Override
        public void handleMouseInput() throws IOException {
            super.handleMouseInput();
            int d = Mouse.getEventDWheel();
            if (d != 0) {
                scroll -= (d > 0 ? 1 : -1) * 20;
                if (scroll < 0) scroll = 0;
                int ms = maxScroll();
                if (scroll > ms) scroll = ms;
            }
        }

        @Override
        protected void keyTyped(char c, int code) throws IOException {
            if (searchFocused) {
                if (code == Keyboard.KEY_ESCAPE) {
                    searchFocused = false;
                } else if (code == Keyboard.KEY_BACK) {
                    if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
                    scroll = 0;
                } else if (ChatAllowedCharacters.isAllowedCharacter(c) && search.length() < 24) {
                    search += c;
                    scroll = 0;
                }
                return;
            }
            super.keyTyped(c, code);
        }

        @Override
        protected void mouseClicked(int mx, int my, int btn) throws IOException {
            if (btn != 0 && btn != 1) return;
            layout();

            // busca
            searchFocused = btn == 0 && in(mx, my, x0 + SIDEW + 14, y0 + 7, 150, 16);
            if (searchFocused) return;

            if (btn == 0) {
                // voltar
                if (in(mx, my, x0 - 28, y0, 22, 22)) {
                    saveSettings();
                    this.mc.displayGuiScreen(parent);
                    return;
                }
                // categorias
                for (int i = 0; i < PAGES.length; i++) {
                    int py = y0 + HEADH + 10 + i * 16;
                    if (in(mx, my, x0, py - 3, SIDEW - 3, 16)) {
                        page = i;
                        search = "";
                        scroll = 0;
                        return;
                    }
                }
            }

            // cards
            if (!(my >= y0 + HEADH && my < y0 + winH && mx >= x0 + SIDEW && mx < x0 + winW)) return;
            int cx = cardX(), cw = cardW(), right = cx + cw - 10;

            for (Row r : rows) {
                if (r.e == null) continue;
                int ry = contentTop() + r.y - scroll;
                if (!in(mx, my, cx, ry, cw, r.h)) continue;
                Entry e = r.e;
                int cy = ry + r.h / 2;

                if (e.type == T_TOGGLE) {
                    if (btn == 0) {
                        flip(e.key);
                        saveSettings();
                    }
                } else if (e.type == T_SLIDER) {
                    if (!sliderActive(e.key)) return;
                    int sx = right - SLW;
                    if (in(mx, my, sx - 6, cy - 14, SLW + 12, 28)) {
                        if (btn == 1) {
                            resetOne(e.key);
                            saveSettings();
                        } else {
                            dragging = e.key;
                            dragSx = sx;
                            set(e.key, valueAt(e.key, mx, sx));
                        }
                    }
                } else if (e.type == T_SEG) {
                    int sx = right - SEGW;
                    if (btn == 0 && in(mx, my, sx, cy - 8, SEGW, 16)) {
                        int idx = (mx - sx) / 38;
                        transition = Math.max(0, Math.min(2, idx));
                        saveSettings();
                    }
                } else {
                    if (btn == 0 && in(mx, my, right - 60, cy - 8, 60, 16)) {
                        resetAll();
                        saveSettings();
                    }
                }
                return;
            }
        }
    }
}
