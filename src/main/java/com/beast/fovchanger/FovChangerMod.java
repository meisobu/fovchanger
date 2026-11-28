package com.beast.fovchanger;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Items;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * FovChanger - client-side, Forge 1.8.9.
 *
 * - O FOV normal e o do proprio Minecraft (slider das opcoes): 76 continua 76.
 * - FOV de sprint: valor em graus. "reset" volta ao comportamento exato do vanilla.
 * - Smooth: suaviza a transicao do FOV (sprint, pocao de velocidade, voo),
 *   sem mexer no zoom do arco.
 *
 * Comando: /fovchanger [sprint <graus|reset> | smooth <on|off>]
 */
@Mod(modid = "fovchangerfast", name = "FovChanger", version = "1.0", clientSideOnly = true)
public class FovChangerMod {

    // No vanilla, correr multiplica o FOV por 1.15 ((1.3 + 1) / 2)
    private static final float VANILLA_SPRINT = 1.15F;
    private static final float SMOOTH_FACTOR = 0.3F;

    // 0 = vanilla (reset)
    public static float sprintFov = 0F;
    public static boolean smooth = true;

    private float smoothed = Float.NaN;

    private final Minecraft mc = Minecraft.getMinecraft();

    @EventHandler
    public void init(FMLInitializationEvent event) {
        loadSettings();
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new FovCommand());
    }

    // Disparado 1x por tick com o multiplicador de FOV do jogador.
    // O vanilla continua fazendo a animacao e a interpolacao por partialTicks depois disso.
    @SubscribeEvent
    public void onFov(FOVUpdateEvent event) {
        if (event.entity != mc.thePlayer) return;

        float target = event.newfov;
        float base = mc.gameSettings.fovSetting;

        if (sprintFov > 0F && base > 0F && event.entity.isSprinting()) {
            target *= sprintFov / (base * VANILLA_SPRINT);
        }

        boolean bow = event.entity.isUsingItem()
                && event.entity.getItemInUse() != null
                && event.entity.getItemInUse().getItem() == Items.bow;

        if (!smooth || bow || Float.isNaN(smoothed) || Float.isInfinite(smoothed)) {
            smoothed = target;
        } else {
            smoothed += (target - smoothed) * SMOOTH_FACTOR;
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
            smooth = Boolean.parseBoolean(p.getProperty("smooth", "true"));
        } catch (Exception e) {
            System.err.println("[FovChanger] Load failed: " + e.getMessage());
        }
    }

    private static void saveSettings() {
        if (cfgFile == null) return;
        try (FileWriter w = new FileWriter(cfgFile)) {
            Properties p = new Properties();
            p.setProperty("sprintFov", String.valueOf(sprintFov));
            p.setProperty("smooth", String.valueOf(smooth));
            p.store(w, "FovChanger Config");
        } catch (Exception e) {
            System.err.println("[FovChanger] Save failed: " + e.getMessage());
        }
    }

    private static float clampSprint(float v) {
        if (v <= 0F) return 0F;
        return Math.max(30F, Math.min(140F, v));
    }

    // ==================== COMANDO ====================
    public static class FovCommand extends CommandBase {

        @Override
        public String getCommandName() {
            return "fovchanger";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/fovchanger [sprint <graus|reset>] [smooth <on|off>]";
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
                return getListOfStringsMatchingLastWord(args, "sprint", "smooth");
            }
            if (args.length == 2) {
                if ("sprint".equalsIgnoreCase(args[0])) {
                    return getListOfStringsMatchingLastWord(args, "reset");
                }
                if ("smooth".equalsIgnoreCase(args[0])) {
                    return getListOfStringsMatchingLastWord(args, "on", "off");
                }
            }
            return null;
        }

        private static String fmt(float v) {
            return String.format(Locale.US, "%.1f", v);
        }

        private void sendStatus(ICommandSender sender) {
            float base = Minecraft.getMinecraft().gameSettings.fovSetting;
            String sprint = sprintFov > 0F
                    ? fmt(sprintFov)
                    : fmt(base * VANILLA_SPRINT) + " (vanilla)";
            sender.addChatMessage(new ChatComponentText(
                    "[ fovchanger ] : [ fov : " + fmt(base) + " ] [ sprint : " + sprint + " ] "
                            + "[ smooth : " + (smooth ? "on" : "off") + " ]"));
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                sendStatus(sender);
                return;
            }

            String cmd = args[0].toLowerCase();

            if ("sprint".equals(cmd) && args.length > 1) {
                if ("reset".equalsIgnoreCase(args[1])) {
                    sprintFov = 0F;
                } else {
                    try {
                        float v = Float.parseFloat(args[1].replace(',', '.'));
                        sprintFov = clampSprint(v);
                    } catch (NumberFormatException e) {
                        sender.addChatMessage(new ChatComponentText("/fovchanger sprint <30-140|reset>"));
                        return;
                    }
                }
            } else if ("smooth".equals(cmd) && args.length > 1) {
                if ("on".equalsIgnoreCase(args[1])) {
                    smooth = true;
                } else if ("off".equalsIgnoreCase(args[1])) {
                    smooth = false;
                } else {
                    sender.addChatMessage(new ChatComponentText("/fovchanger smooth <on|off>"));
                    return;
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
