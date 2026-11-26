package com.beast.fovchanger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
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
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

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
 * Menu no estilo Essential.
 *
 * Abrir o menu: tecla K (mude em Opcoes > Controles) ou /fov
 */
@Mod(modid = "fovchangerplus", name = "FOV Changer Plus", version = "1.2", clientSideOnly = true)
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

    // Ultimo multiplicador (sem o arco) calculado a partir do FOVUpdateEvent do proprio jogo.
    private float eventRatio = 1.0F;
    private boolean eventValid = false;

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

    // ==================== MODELO DO VANILLA ====================
    // No Minecraft 1.8.9:
    //   FOV final = fovSetting * modificador
    //   modificador = (velocidade / velocidadeBase + 1) / 2     (x1.1 voando, x(1 - 15% * zoom) com arco)
    //   velocidade  = produto dos modificadores: Speed +20%/nivel, Slowness -15%/nivel, sprint +30%
    // O jogo faz o lerp (50% por tick) e a interpolacao por partialTicks desse modificador.
    // O mod NAO recalcula isso do zero: ele pega o valor REAL do jogo (FOVUpdateEvent) e so troca
    // os valores de FOV de cada estado, mantendo tudo que o vanilla faz (voar, arco, outros mods
    // que mexem na velocidade, agua, morte, etc).

    // Velocidade esperada pelo vanilla com base no estado atual (efeitos + sprint).
    private static float vanillaExpectedMod(EntityPlayer p) {
        float r = 1.0F;
        PotionEffect speed = p.getActivePotionEffect(Potion.moveSpeed);
        PotionEffect slow = p.getActivePotionEffect(Potion.moveSlowdown);
        if (speed != null) r *= 1.0F + 0.2F * (speed.getAmplifier() + 1);
        if (slow != null) r *= 1.0F - 0.15F * (slow.getAmplifier() + 1);
        if (p.isSprinting()) r *= 1.3F;
        if (r < 0.0F) r = 0.0F;
        return (r + 1.0F) / 2.0F;
    }

    // Fator do arco (ate -15%). partial = 0 no tick (igual ao vanilla), partialTicks no frame.
    private static float bowFactor(EntityPlayer p, float partial) {
        if (p.isUsingItem()) {
            ItemStack inUse = p.getItemInUse();
            if (inUse != null && inUse.getItem() == Items.bow) {
                float f = (p.getItemInUseDuration() + partial) / 20.0F;
                f = f > 1.0F ? 1.0F : f * f;
                return 1.0F - f * 0.15F;
            }
        }
        return 1.0F;
    }

    // Converte "FOV quando SO esse estado esta ativo" na velocidade equivalente do vanilla.
    // Se FOV == Normal da 1.0 (sem mudanca).
    private static float customR(int fov) {
        float r = 2.0F * fov / (float) normalFov - 1.0F;
        return r < 0.0F ? 0.0F : r;
    }

    // Modificador com os FOVs escolhidos. Os estados se combinam como no jogo (multiplicando).
    private static float customMod(EntityPlayer p) {
        float r = 1.0F;
        if (dynamicEffect) {
            PotionEffect speed = p.getActivePotionEffect(Potion.moveSpeed);
            PotionEffect slow = p.getActivePotionEffect(Potion.moveSlowdown);
            if (speed != null) {
                int a = speed.getAmplifier();
                if (a >= 1) r *= customR(speed2Fov) * (1.0F + 0.2F * (a + 1)) / 1.4F;
                else r *= customR(speed1Fov);
            }
            if (slow != null) {
                int a = slow.getAmplifier();
                float k = a >= 1 ? Math.max(0.0F, 1.0F - 0.15F * (a + 1)) / 0.85F : 1.0F;
                r *= customR(slowFov) * k;
            }
        }
        if (dynamicSprint && p.isSprinting()) {
            r *= customR(sprintFov);
        }
        return (r + 1.0F) / 2.0F;
    }

    // Multiplicador final (sem arco). vanillaNoBow = modificador real do jogo sem o arco
    // (ou <= 0 se nao tiver, ai usa so o modelo).
    // O "residual" e tudo que o vanilla aplica alem do modelo (voar, atributos de outros mods...).
    private static float ratioNoBow(EntityPlayer p, float vanillaNoBow) {
        float residual = 1.0F;
        if (vanillaNoBow > 0.0F && p.capabilities.getWalkSpeed() > 0.0F) {
            residual = vanillaNoBow / vanillaExpectedMod(p);
            if (Float.isNaN(residual) || Float.isInfinite(residual)) residual = 1.0F;
        }
        return customMod(p) * residual;
    }

    private EntityPlayer viewPlayer() {
        Entity e = mc.getRenderViewEntity();
        return e instanceof EntityPlayer ? (EntityPlayer) e : mc.thePlayer;
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
            eventValid = false;
            smoothFov = -1.0F;
            lastNs = 0L;
        }
    }

    // Chamado pelo proprio jogo 1x por tick (EntityRenderer.updateFovModifierHand).
    // event.fov = modificador REAL do vanilla (sprint, speed, slowness, voo, arco, atributos).
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onFovUpdate(FOVUpdateEvent event) {
        if (!enabled) return;

        EntityPlayer p = event.entity;
        float bow = bowFactor(p, 0.0F);
        eventRatio = ratioNoBow(p, event.fov / bow);
        eventValid = true;

        if (transition == TYPE_VANILLA) {
            // Modo Vanilla: devolve o modificador pro jogo, que faz o lerp e a interpolacao sozinho.
            float m = eventRatio * (dynamicBow ? bow : 1.0F);
            if (m < 0.1F) m = 0.1F;   // limites do proprio vanilla
            if (m > 1.5F) m = 1.5F;
            event.newfov = m;
        } else {
            // Old / Smooth: o mod controla a transicao por frame, entao zera o do vanilla.
            event.newfov = 1.0F;
        }
    }

    // Antes de cada frame: coloca o FOV base no lugar do fovSetting do jogo.
    // Depois do frame: devolve o valor original (assim o options.txt nao e alterado).
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // seguranca: se o END nao rodou (erro no frame), restaura antes de guardar de novo
            if (overridden) {
                mc.gameSettings.fovSetting = savedFov;
                overridden = false;
            }
            if (!enabled || mc.thePlayer == null || mc.theWorld == null) {
                smoothFov = -1.0F;
                lastNs = 0L;
                return;
            }

            float value;
            if (transition == TYPE_VANILLA) {
                // o jogo multiplica fovSetting pelo modificador (que ja vem do evento)
                smoothFov = -1.0F;
                lastNs = 0L;
                value = normalFov;
            } else {
                EntityPlayer p = viewPlayer();
                boolean useEvent = eventValid && mc.getRenderViewEntity() instanceof AbstractClientPlayer;
                float ratio = useEvent ? eventRatio : ratioNoBow(p, -1.0F);
                float target = normalFov * ratio;

                long now = System.nanoTime();
                float dt = lastNs == 0L ? 0.0F : (now - lastNs) / 1.0E9F;
                lastNs = now;
                if (dt > 0.1F) dt = 0.1F;

                float base;
                if (transition == TYPE_OLD) {
                    base = target;
                } else {
                    if (smoothFov < 0.0F) smoothFov = target;
                    base = smoothFov + (target - smoothFov) * (1.0F - (float) Math.exp(-smoothSpeed * dt));
                }
                smoothFov = base;

                value = base * (dynamicBow ? bowFactor(p, event.renderTickTime) : 1.0F);
            }

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

    // ==================== MENU (estilo Essential) ====================
    public static class FovScreen extends GuiScreen {
        private static final int W = 340;
        private static final int H = 226;
        private static final int SW = 150; // largura dos sliders / coluna

        private static final int ACCENT = 0xFF3EA6FF;
        private static final int ACCENT_HOVER = 0xFF5BB6FF;
        private static final int BORDER = 0xFF2A2A2A;
        private static final int BG = 0xFF181818;
        private static final int SEP = 0xFF262626;
        private static final int TRACK = 0xFF2F2F2F;
        private static final int TEXT = 0xFFE6E6E6;
        private static final int TEXT_DIM = 0xFF9AA0A6;
        private static final int TEXT_OFF = 0xFF5A5A5A;

        private static final String[] LABELS = {
            "Normal FOV", "Sprinting FOV", "Speed I FOV", "Speed II FOV", "Slowness FOV", "Smooth Speed"
        };

        private final GuiScreen parent;
        private int x0, y0;
        private int dragging = -1;
        private final float[] anim = new float[4]; // 0 = master, 1 = sprint, 2 = bow, 3 = effect
        private long lastMs = 0L;

        public FovScreen(GuiScreen parent) {
            this.parent = parent;
        }

        // ---------- desenho ----------
        private static void rrect(int x, int y, int w, int h, int r, int c) {
            r = Math.min(r, Math.min(w, h) / 2);
            if (r <= 0) {
                Gui.drawRect(x, y, x + w, y + h, c);
                return;
            }
            for (int i = 0; i < r; i++) {
                double dy = r - i - 0.5;
                int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
                Gui.drawRect(x + inset, y + i, x + w - inset, y + i + 1, c);
                Gui.drawRect(x + inset, y + h - i - 1, x + w - inset, y + h - i, c);
            }
            Gui.drawRect(x, y + r, x + w, y + h - r, c);
        }

        private static int lerpColor(int a, int b, float t) {
            int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            int r = (int) (ar + (br - ar) * t);
            int g = (int) (ag + (bg - ag) * t);
            int bl = (int) (ab + (bb - ab) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | bl;
        }

        private static boolean in(int mx, int my, int x, int y, int w, int h) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        private boolean[] states() {
            return new boolean[] { enabled, dynamicSprint, dynamicBow, dynamicEffect };
        }

        // ---------- geometria ----------
        private int sliderX(int key) { return key == K_SMOOTH ? x0 + 178 : x0 + 12; }
        private int sliderY(int key) { return key == K_SMOOTH ? y0 + 154 : y0 + 40 + key * 30; }
        private int toggleX() { return x0 + 178 + SW - 26; }
        private int toggleY(int i) { return i == 0 ? y0 + 9 : y0 + 40 + (i - 1) * 22; }
        private int segX() { return x0 + 178; }
        private int segY() { return y0 + 130; }

        private int valueAt(int key, int mouseX) {
            float f = (float) (mouseX - sliderX(key)) / (float) SW;
            if (f < 0.0F) f = 0.0F;
            if (f > 1.0F) f = 1.0F;
            return Math.round(min(key) + f * (max(key) - min(key)));
        }

        @Override
        public void initGui() {
            x0 = (this.width - W) / 2;
            y0 = (this.height - H) / 2;
            boolean[] st = states();
            for (int i = 0; i < 4; i++) anim[i] = st[i] ? 1.0F : 0.0F;
            lastMs = 0L;
        }

        @Override
        public void drawScreen(int mx, int my, float partialTicks) {
            // animacao dos toggles
            long now = Minecraft.getSystemTime();
            float dt = lastMs == 0L ? 0.0F : (now - lastMs) / 1000.0F;
            lastMs = now;
            boolean[] st = states();
            for (int i = 0; i < 4; i++) {
                anim[i] += ((st[i] ? 1.0F : 0.0F) - anim[i]) * Math.min(1.0F, dt * 16.0F);
            }

            // arrastar slider
            if (dragging >= 0) {
                if (Mouse.isButtonDown(0)) {
                    set(dragging, valueAt(dragging, mx));
                } else {
                    dragging = -1;
                    saveSettings();
                }
            }

            // fundo escurecido + janela
            Gui.drawRect(0, 0, this.width, this.height, 0x88000000);
            rrect(x0 - 1, y0 - 1, W + 2, H + 2, 7, BORDER);
            rrect(x0, y0, W, H, 6, BG);

            // cabecalho
            this.fontRendererObj.drawString("FOV Changer", x0 + 12, y0 + 10, 0xFFFFFFFF);
            this.fontRendererObj.drawString("Plus", x0 + 12 + this.fontRendererObj.getStringWidth("FOV Changer ") , y0 + 10, ACCENT);
            Gui.drawRect(x0, y0 + 28, x0 + W, y0 + 29, SEP);
            String enLabel = enabled ? "Enabled" : "Disabled";
            this.fontRendererObj.drawString(enLabel,
                toggleX() - 8 - this.fontRendererObj.getStringWidth(enLabel), y0 + 11, enabled ? TEXT : TEXT_DIM);
            drawToggle(toggleX(), toggleY(0), anim[0]);

            // sliders da esquerda
            for (int k = 0; k <= K_SLOW; k++) drawSlider(k, mx, my, true);

            // coluna da direita: toggles
            String[] tl = { "Dynamic Sprint", "Dynamic Bow", "Dynamic Status Effect" };
            for (int i = 1; i <= 3; i++) {
                int ty = toggleY(i);
                this.fontRendererObj.drawString(tl[i - 1], x0 + 178, ty + 2, TEXT);
                drawToggle(toggleX(), ty, anim[i]);
            }

            // transicao (controle segmentado)
            this.fontRendererObj.drawString("Transition", x0 + 178, y0 + 118, TEXT_DIM);
            rrect(segX(), segY(), SW, 18, 5, 0xFF262626);
            for (int i = 0; i < 3; i++) {
                int cx = segX() + i * 50;
                boolean active = transition == i;
                boolean hover = in(mx, my, cx, segY(), 50, 18);
                if (active) rrect(cx + 1, segY() + 1, 48, 16, 4, ACCENT);
                else if (hover) rrect(cx + 1, segY() + 1, 48, 16, 4, 0xFF333333);
                int w = this.fontRendererObj.getStringWidth(TYPE_NAMES[i]);
                this.fontRendererObj.drawString(TYPE_NAMES[i], cx + (50 - w) / 2, segY() + 5,
                    active ? 0xFFFFFFFF : TEXT_DIM);
            }

            // slider do smooth (so ativo no modo Smooth)
            drawSlider(K_SMOOTH, mx, my, transition == TYPE_SMOOTH);

            // botoes
            drawButton(x0 + 178, y0 + 192, 72, 20, "Reset All", false, mx, my);
            drawButton(x0 + 256, y0 + 192, 72, 20, "Done", true, mx, my);

            // dica
            this.fontRendererObj.drawString("Right-click a slider to reset", x0 + 12, y0 + 206, 0xFF666666);
        }

        private void drawToggle(int x, int y, float a) {
            rrect(x, y, 26, 12, 6, lerpColor(0xFF3A3A3A, ACCENT, a));
            int kx = x + 2 + Math.round(a * 14.0F);
            rrect(kx, y + 2, 8, 8, 4, 0xFFFFFFFF);
        }

        private void drawSlider(int key, int mx, int my, boolean active) {
            int sx = sliderX(key);
            int sy = sliderY(key);
            int v = get(key);
            float f = (v - min(key)) / (float) (max(key) - min(key));

            String vs = String.valueOf(v);
            if (key != K_SMOOTH) {
                if (v == 70) vs += " (Normal)";
                else if (v == 110) vs += " (Quake Pro)";
            } else if (v == DEFAULT_SMOOTH) {
                vs += " (Vanilla)";
            }

            this.fontRendererObj.drawString(LABELS[key], sx, sy, active ? TEXT : TEXT_OFF);
            int vw = this.fontRendererObj.getStringWidth(vs);
            this.fontRendererObj.drawString(vs, sx + SW - vw, sy, active ? TEXT_DIM : TEXT_OFF);

            int ty = sy + 14;
            rrect(sx, ty, SW, 4, 2, TRACK);
            int fillW = Math.max(4, (int) (f * SW));
            rrect(sx, ty, fillW, 4, 2, active ? ACCENT : 0xFF444444);

            boolean hover = active && (dragging == key || in(mx, my, sx - 4, sy, SW + 8, 26));
            int r = hover ? 6 : 5;
            int kx = sx + (int) (f * SW);
            rrect(kx - r, ty + 2 - r, r * 2, r * 2, r, active ? 0xFFFFFFFF : 0xFF777777);
        }

        private void drawButton(int x, int y, int w, int h, String label, boolean primary, int mx, int my) {
            boolean hover = in(mx, my, x, y, w, h);
            int c = primary ? (hover ? ACCENT_HOVER : ACCENT) : (hover ? 0xFF353535 : 0xFF2A2A2A);
            rrect(x, y, w, h, 5, c);
            int tw = this.fontRendererObj.getStringWidth(label);
            this.fontRendererObj.drawString(label, x + (w - tw) / 2, y + (h - 8) / 2, 0xFFFFFFFF);
        }

        // ---------- input ----------
        @Override
        protected void mouseClicked(int mx, int my, int btn) {
            if (btn != 0 && btn != 1) return;

            // sliders
            for (int k = 0; k <= K_SMOOTH; k++) {
                if (k == K_SMOOTH && transition != TYPE_SMOOTH) continue;
                if (in(mx, my, sliderX(k) - 4, sliderY(k), SW + 8, 26)) {
                    if (btn == 1) {
                        resetOne(k);
                        saveSettings();
                    } else {
                        dragging = k;
                        set(k, valueAt(k, mx));
                    }
                    return;
                }
            }
            if (btn != 0) return;

            // toggle principal
            if (in(mx, my, toggleX() - 80, y0 + 4, 80 + 26, 20)) {
                enabled = !enabled;
                saveSettings();
                return;
            }
            // toggles da direita
            for (int i = 1; i <= 3; i++) {
                if (in(mx, my, x0 + 178, toggleY(i) - 2, SW, 16)) {
                    if (i == 1) dynamicSprint = !dynamicSprint;
                    else if (i == 2) dynamicBow = !dynamicBow;
                    else dynamicEffect = !dynamicEffect;
                    saveSettings();
                    return;
                }
            }
            // transicao
            if (in(mx, my, segX(), segY(), SW, 18)) {
                int idx = (mx - segX()) / 50;
                transition = Math.max(0, Math.min(2, idx));
                saveSettings();
                return;
            }
            // botoes
            if (in(mx, my, x0 + 178, y0 + 192, 72, 20)) {
                resetAll();
                saveSettings();
                return;
            }
            if (in(mx, my, x0 + 256, y0 + 192, 72, 20)) {
                saveSettings();
                this.mc.displayGuiScreen(parent);
            }
        }

        @Override
        protected void keyTyped(char c, int code) throws IOException {
            if (openGuiKey != null && code == openGuiKey.getKeyCode()) {
                saveSettings();
                this.mc.displayGuiScreen(parent);
                return;
            }
            super.keyTyped(c, code);
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
