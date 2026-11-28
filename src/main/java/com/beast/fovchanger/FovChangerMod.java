package com.beast.fovchanger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.util.BlockPos;
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

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * FovChanger - client-side, Forge 1.8.9.
 *
 * Tudo parte do comportamento EXATO do vanilla. Cada opcao so altera a parte dela:
 *  - FOV base: slider de FOV do proprio Minecraft (76 continua 76).
 *  - Sprint: FOV em graus ao correr (reset = vanilla, base x 1.15).
 *  - Velocidade: forca do efeito de pocao de velocidade/lentidao (100% = vanilla).
 *  - Voo: forca do aumento de FOV ao voar (100% = vanilla).
 *  - Arco: forca do zoom ao puxar o arco (100% = vanilla).
 *  - Suavidade: suaviza a transicao do FOV (0% = vanilla), sem atrasar o zoom do arco.
 *
 * Menu: tecla K. Comando: /fovchanger
 */
@Mod(modid = "fovchangerfast", name = "FovChanger", version = "1.0", clientSideOnly = true)
public class FovChangerMod {

    // Se true, apertar K de novo com o menu aberto fecha o menu.
    private static final boolean K_CLOSES_MENU = false;

    // Valores do vanilla 1.8.9
    private static final float SPRINT_RATIO = 1.3F;   // correr = +30% de velocidade -> FOV x ((1.3 + 1) / 2)
    private static final float FLY_FACTOR = 0.1F;     // voar = FOV x 1.1
    private static final float BOW_FACTOR = 0.15F;    // arco = FOV x (1 - 0.15 * progresso^2)

    // Configuracoes (valores padrao = vanilla, exceto a suavidade)
    public static float sprintFov = 0F;    // 0 = vanilla (auto)
    public static float speedPct = 100F;
    public static float flyPct = 100F;
    public static float bowPct = 100F;
    public static float smoothPct = 40F;   // 0 = vanilla, 100 = bem macio

    public static KeyBinding menuKey;
    private static boolean openMenuRequested = false;

    private float smoothed = Float.NaN;

    private final Minecraft mc = Minecraft.getMinecraft();

    @EventHandler
    public void init(FMLInitializationEvent event) {
        loadSettings();
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new FovCommand());

        menuKey = new KeyBinding("key.fovchanger.menu", Keyboard.KEY_K, "key.categories.misc");
        ClientRegistry.registerKeyBinding(menuKey);
    }

    // ==================== MENU (TECLA K) ====================
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (mc.currentScreen != null) {
            // Descarta toques de K feitos com algum menu aberto, para nao reabrir/fechar sozinho
            while (menuKey.isPressed()) {
                // vazio
            }
            return;
        }

        if (mc.thePlayer == null) return;

        boolean open = false;
        while (menuKey.isPressed()) {
            open = true;
        }
        if (open || openMenuRequested) {
            openMenuRequested = false;
            mc.displayGuiScreen(new FovMenu());
        }
    }

    // ==================== FOV ====================
    private static boolean isDefault() {
        return sprintFov <= 0F && speedPct == 100F && flyPct == 100F && bowPct == 100F;
    }

    private static boolean usingBow(EntityPlayer p) {
        return p.isUsingItem() && p.getItemInUse() != null && p.getItemInUse().getItem() == Items.bow;
    }

    // Fator de suavizacao por tick: 1 = sem suavizar (vanilla), menor = mais macio.
    // 40% -> 0.66, 100% -> 0.15
    private static float smoothFactor() {
        return 1F - 0.85F * (smoothPct / 100F);
    }

    /**
     * Razao entre o FOV com as suas configuracoes e o FOV que o vanilla usaria.
     * Com tudo no padrao retorna exatamente 1 (nada e alterado).
     */
    private float correction(EntityPlayer p) {
        if (isDefault()) return 1F;

        float walk = p.capabilities.getWalkSpeed();
        if (walk == 0F) return 1F;

        float base = mc.gameSettings.fovSetting;
        float ratio = (float) (p.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getAttributeValue() / walk);
        boolean sprinting = p.isSprinting();
        boolean flying = p.capabilities.isFlying;

        // Parte do efeito (pocao de velocidade/lentidao) sem o sprint
        float effect = sprinting ? ratio / SPRINT_RATIO : ratio;

        // Progresso do arco, igual ao vanilla
        float bow = 0F;
        if (usingBow(p)) {
            float f1 = (float) p.getItemInUseDuration() / 20F;
            f1 = f1 > 1F ? 1F : f1 * f1;
            bow = f1 * BOW_FACTOR;
        }

        // FOV do vanilla
        float vanilla = (flying ? 1F + FLY_FACTOR : 1F) * ((ratio + 1F) / 2F) * (1F - bow);

        // FOV com as configuracoes
        float sprintRatio = SPRINT_RATIO;
        if (sprintFov > 0F && base > 0F) {
            sprintRatio = 2F * (sprintFov / base) - 1F;
        }
        float customEffect = 1F + (effect - 1F) * (speedPct / 100F);
        float customRatio = customEffect * (sprinting ? sprintRatio : 1F);
        float custom = (flying ? 1F + FLY_FACTOR * (flyPct / 100F) : 1F)
                * ((customRatio + 1F) / 2F)
                * (1F - bow * (bowPct / 100F));

        if (vanilla <= 0F || Float.isNaN(vanilla) || Float.isNaN(custom)
                || Float.isInfinite(vanilla) || Float.isInfinite(custom)) {
            return 1F;
        }
        return custom / vanilla;
    }

    // Disparado 1x por tick com o multiplicador de FOV do jogador.
    // O vanilla continua fazendo a animacao e a interpolacao por partialTicks depois disso.
    @SubscribeEvent
    public void onFov(FOVUpdateEvent event) {
        if (event.entity != mc.thePlayer) return;

        float target = event.newfov * correction(event.entity);
        float k = smoothFactor();

        if (k >= 1F || usingBow(event.entity) || Float.isNaN(smoothed) || Float.isInfinite(smoothed)) {
            smoothed = target;
        } else {
            smoothed += (target - smoothed) * k;
        }

        event.newfov = smoothed;
    }

    // ==================== CONFIG ====================
    private static File cfgFile;

    private static void loadSettings() {
        try {
            File dir = new File(Minecraft.getMinecraft().mcDataDir, "config");
            if (!dir.exists()) dir.mkdirs();
            cfgFile = new File(dir, "fovchanger.properties");
            if (!cfgFile.exists()) {
                saveSettings();
                return;
            }
            Properties p = new Properties();
            try (FileReader r = new FileReader(cfgFile)) {
                p.load(r);
            }
            sprintFov = clampSprint(Float.parseFloat(p.getProperty("sprintFov", "0")));
            speedPct = clampPct(Float.parseFloat(p.getProperty("speedPct", "100")));
            flyPct = clampPct(Float.parseFloat(p.getProperty("flyPct", "100")));
            bowPct = clampPct(Float.parseFloat(p.getProperty("bowPct", "100")));

            String sp = p.getProperty("smoothPct");
            if (sp != null) {
                smoothPct = clampSmooth(Float.parseFloat(sp));
            } else if ("false".equalsIgnoreCase(p.getProperty("smooth"))) {
                smoothPct = 0F; // config antiga com smooth desligado
            } else {
                smoothPct = 40F;
            }
        } catch (Exception e) {
            System.err.println("[FovChanger] Load failed: " + e.getMessage());
        }
    }

    public static void saveSettings() {
        if (cfgFile == null) return;
        try (FileWriter w = new FileWriter(cfgFile)) {
            Properties p = new Properties();
            p.setProperty("sprintFov", String.valueOf(sprintFov));
            p.setProperty("speedPct", String.valueOf(speedPct));
            p.setProperty("flyPct", String.valueOf(flyPct));
            p.setProperty("bowPct", String.valueOf(bowPct));
            p.setProperty("smoothPct", String.valueOf(smoothPct));
            p.store(w, "FovChanger Config");
        } catch (Exception e) {
            System.err.println("[FovChanger] Save failed: " + e.getMessage());
        }
    }

    private static float clampSprint(float v) {
        if (v <= 0F) return 0F;
        return Math.max(30F, Math.min(140F, v));
    }

    private static float clampPct(float v) {
        return Math.max(0F, Math.min(200F, v));
    }

    private static float clampSmooth(float v) {
        return Math.max(0F, Math.min(100F, v));
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static float vanillaSprintFov() {
        return Minecraft.getMinecraft().gameSettings.fovSetting * ((SPRINT_RATIO + 1F) / 2F);
    }

    // ==================== SLIDER ====================
    public interface Binding {
        float get();

        void set(float v);

        String text(float v);
    }

    public static class FovSlider extends GuiButton {
        private final Binding binding;
        private final float min;
        private final float max;
        private final float step;
        private float pos;
        private boolean dragging;

        public FovSlider(int id, int x, int y, int w, int h, float min, float max, float step, Binding binding) {
            super(id, x, y, w, h, "");
            this.min = min;
            this.max = max;
            this.step = step;
            this.binding = binding;
            refresh();
        }

        public void refresh() {
            float v = binding.get();
            pos = clamp01((v - min) / (max - min));
            displayString = binding.text(v);
        }

        private static float clamp01(float v) {
            return Math.max(0F, Math.min(1F, v));
        }

        private void updateFromMouse(int mouseX) {
            float raw = clamp01((float) (mouseX - (xPosition + 4)) / (float) (width - 8));
            float v = min + Math.round(raw * (max - min) / step) * step;
            v = Math.max(min, Math.min(max, v));
            binding.set(v);
            pos = clamp01((v - min) / (max - min));
            displayString = binding.text(binding.get());
        }

        @Override
        protected int getHoverState(boolean mouseOver) {
            return 0;
        }

        @Override
        protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            if (dragging) {
                updateFromMouse(mouseX);
            }
            mc.getTextureManager().bindTexture(buttonTextures);
            GlStateManager.color(1F, 1F, 1F, 1F);
            int handleX = xPosition + (int) (pos * (float) (width - 8));
            drawTexturedModalRect(handleX, yPosition, 0, 66, 4, 20);
            drawTexturedModalRect(handleX + 4, yPosition, 196, 66, 4, 20);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (super.mousePressed(mc, mouseX, mouseY)) {
                updateFromMouse(mouseX);
                dragging = true;
                return true;
            }
            return false;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            dragging = false;
        }
    }

    // ==================== MENU ====================
    public static class FovMenu extends GuiScreen {

        private static final int ID_DONE = 0;
        private static final int ID_RESET_ALL = 21;
        private static final int ID_RESET_SPRINT = 11;
        private static final int ID_RESET_SPEED = 12;
        private static final int ID_RESET_FLY = 13;
        private static final int ID_RESET_BOW = 14;
        private static final int ID_RESET_SMOOTH = 15;

        private FovSlider sprintSlider;
        private FovSlider speedSlider;
        private FovSlider flySlider;
        private FovSlider bowSlider;
        private FovSlider smoothSlider;

        private static String pct(String label, float v) {
            return label + ": " + (int) v + "%" + (v == 100F ? " (vanilla)" : "");
        }

        @Override
        public void initGui() {
            buttonList.clear();

            int cx = width / 2;
            int y = height / 6;

            sprintSlider = new FovSlider(1, cx - 100, y, 160, 20, 30F, 140F, 0.5F, new Binding() {
                public float get() {
                    return sprintFov > 0F ? sprintFov : vanillaSprintFov();
                }

                public void set(float v) {
                    sprintFov = v;
                }

                public String text(float v) {
                    return "FOV de Sprint: " + fmt(v) + (sprintFov > 0F ? "" : " (vanilla)");
                }
            });
            speedSlider = new FovSlider(2, cx - 100, y + 24, 160, 20, 0F, 200F, 5F, new Binding() {
                public float get() {
                    return speedPct;
                }

                public void set(float v) {
                    speedPct = v;
                }

                public String text(float v) {
                    return pct("Efeito de Velocidade", v);
                }
            });
            flySlider = new FovSlider(3, cx - 100, y + 48, 160, 20, 0F, 200F, 5F, new Binding() {
                public float get() {
                    return flyPct;
                }

                public void set(float v) {
                    flyPct = v;
                }

                public String text(float v) {
                    return pct("FOV ao Voar", v);
                }
            });
            bowSlider = new FovSlider(4, cx - 100, y + 72, 160, 20, 0F, 200F, 5F, new Binding() {
                public float get() {
                    return bowPct;
                }

                public void set(float v) {
                    bowPct = v;
                }

                public String text(float v) {
                    return pct("Zoom do Arco", v);
                }
            });
            smoothSlider = new FovSlider(5, cx - 100, y + 96, 160, 20, 0F, 100F, 5F, new Binding() {
                public float get() {
                    return smoothPct;
                }

                public void set(float v) {
                    smoothPct = v;
                }

                public String text(float v) {
                    return "Suavidade: " + (int) v + "%" + (v == 0F ? " (vanilla)" : "");
                }
            });

            buttonList.add(sprintSlider);
            buttonList.add(speedSlider);
            buttonList.add(flySlider);
            buttonList.add(bowSlider);
            buttonList.add(smoothSlider);

            buttonList.add(new GuiButton(ID_RESET_SPRINT, cx + 64, y, 36, 20, "Reset"));
            buttonList.add(new GuiButton(ID_RESET_SPEED, cx + 64, y + 24, 36, 20, "Reset"));
            buttonList.add(new GuiButton(ID_RESET_FLY, cx + 64, y + 48, 36, 20, "Reset"));
            buttonList.add(new GuiButton(ID_RESET_BOW, cx + 64, y + 72, 36, 20, "Reset"));
            buttonList.add(new GuiButton(ID_RESET_SMOOTH, cx + 64, y + 96, 36, 20, "Reset"));

            buttonList.add(new GuiButton(ID_RESET_ALL, cx - 100, y + 124, 200, 20, "Resetar tudo"));
            buttonList.add(new GuiButton(ID_DONE, cx - 100, y + 148, 200, 20, "Pronto"));
        }

        @Override
        protected void actionPerformed(GuiButton button) throws IOException {
            switch (button.id) {
                case ID_RESET_SPRINT:
                    sprintFov = 0F;
                    sprintSlider.refresh();
                    break;
                case ID_RESET_SPEED:
                    speedPct = 100F;
                    speedSlider.refresh();
                    break;
                case ID_RESET_FLY:
                    flyPct = 100F;
                    flySlider.refresh();
                    break;
                case ID_RESET_BOW:
                    bowPct = 100F;
                    bowSlider.refresh();
                    break;
                case ID_RESET_SMOOTH:
                    smoothPct = 0F;
                    smoothSlider.refresh();
                    break;
                case ID_RESET_ALL:
                    sprintFov = 0F;
                    speedPct = 100F;
                    flyPct = 100F;
                    bowPct = 100F;
                    smoothPct = 0F;
                    sprintSlider.refresh();
                    speedSlider.refresh();
                    flySlider.refresh();
                    bowSlider.refresh();
                    smoothSlider.refresh();
                    break;
                case ID_DONE:
                    mc.displayGuiScreen(null);
                    break;
                default:
                    break;
            }
            saveSettings();
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) throws IOException {
            if (K_CLOSES_MENU && menuKey != null && keyCode == menuKey.getKeyCode()) {
                mc.displayGuiScreen(null);
                return;
            }
            super.keyTyped(typedChar, keyCode);
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            saveSettings();
        }

        @Override
        public void onGuiClosed() {
            saveSettings();
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawDefaultBackground();
            drawCenteredString(fontRendererObj, "FovChanger", width / 2, height / 6 - 20, 0xFFFFFF);
            super.drawScreen(mouseX, mouseY, partialTicks);
            drawCenteredString(fontRendererObj, "100% = igual ao Minecraft", width / 2, height / 6 + 176, 0xAAAAAA);
        }
    }

    // ==================== COMANDO ====================
    public static class FovCommand extends CommandBase {

        @Override
        public String getCommandName() {
            return "fovchanger";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/fovchanger [menu|sprint|speed|fly|bow|smooth|reset]";
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return true;
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
            if (args.length == 1) {
                return getListOfStringsMatchingLastWord(args, "menu", "sprint", "speed", "fly", "bow", "smooth", "reset");
            }
            if (args.length == 2) {
                String c = args[0].toLowerCase();
                if ("sprint".equals(c) || "speed".equals(c) || "fly".equals(c) || "bow".equals(c)) {
                    return getListOfStringsMatchingLastWord(args, "reset");
                }
                if ("smooth".equals(c)) {
                    return getListOfStringsMatchingLastWord(args, "on", "off", "reset");
                }
            }
            return null;
        }

        private void sendStatus(ICommandSender sender) {
            String sprint = sprintFov > 0F ? fmt(sprintFov) : fmt(vanillaSprintFov()) + " (vanilla)";
            sender.addChatMessage(new ChatComponentText(
                    "[ fovchanger ] : [ fov : " + fmt(Minecraft.getMinecraft().gameSettings.fovSetting) + " ] "
                            + "[ sprint : " + sprint + " ] "
                            + "[ speed : " + (int) speedPct + "% ] "
                            + "[ fly : " + (int) flyPct + "% ] "
                            + "[ bow : " + (int) bowPct + "% ] "
                            + "[ smooth : " + (int) smoothPct + "% ]"));
        }

        private boolean setPct(ICommandSender sender, String name, String arg, int which) {
            float v;
            if ("reset".equalsIgnoreCase(arg)) {
                v = 100F;
            } else {
                try {
                    v = clampPct(Float.parseFloat(arg.replace(',', '.')));
                } catch (NumberFormatException e) {
                    sender.addChatMessage(new ChatComponentText("/fovchanger " + name + " <0-200|reset>"));
                    return false;
                }
            }
            if (which == 0) speedPct = v;
            else if (which == 1) flyPct = v;
            else bowPct = v;
            return true;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                sendStatus(sender);
                return;
            }

            String cmd = args[0].toLowerCase();

            if ("menu".equals(cmd)) {
                // abre no proximo tick, porque o chat fecha a tela logo apos o comando
                openMenuRequested = true;
                return;
            } else if ("reset".equals(cmd)) {
                sprintFov = 0F;
                speedPct = 100F;
                flyPct = 100F;
                bowPct = 100F;
                smoothPct = 0F;
            } else if ("sprint".equals(cmd) && args.length > 1) {
                if ("reset".equalsIgnoreCase(args[1])) {
                    sprintFov = 0F;
                } else {
                    try {
                        sprintFov = clampSprint(Float.parseFloat(args[1].replace(',', '.')));
                    } catch (NumberFormatException e) {
                        sender.addChatMessage(new ChatComponentText("/fovchanger sprint <30-140|reset>"));
                        return;
                    }
                }
            } else if ("speed".equals(cmd) && args.length > 1) {
                if (!setPct(sender, "speed", args[1], 0)) return;
            } else if ("fly".equals(cmd) && args.length > 1) {
                if (!setPct(sender, "fly", args[1], 1)) return;
            } else if ("bow".equals(cmd) && args.length > 1) {
                if (!setPct(sender, "bow", args[1], 2)) return;
            } else if ("smooth".equals(cmd) && args.length > 1) {
                String a = args[1];
                if ("on".equalsIgnoreCase(a)) {
                    smoothPct = 40F;
                } else if ("off".equalsIgnoreCase(a) || "reset".equalsIgnoreCase(a)) {
                    smoothPct = 0F;
                } else {
                    try {
                        smoothPct = clampSmooth(Float.parseFloat(a.replace(',', '.')));
                    } catch (NumberFormatException e) {
                        sender.addChatMessage(new ChatComponentText("/fovchanger smooth <0-100|on|off|reset>"));
                        return;
                    }
                }
            } else {
                sendStatus(sender);
                return;
            }

            saveSettings();
            sendStatus(sender);
        }
    }
}
