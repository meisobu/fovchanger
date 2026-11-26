package com.beast.hitparticles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.world.World;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Properties;

/**
 * HitParticles - client-side, Forge 1.8.9.
 *
 * Comportamento: as particulas de hit (critico e sharpness) aparecem normalmente pelos primeiros
 * hits, depois somem por alguns segundos e voltam sozinhas. Nada alem disso e alterado.
 *
 * Comando: /hitparticles [toggle | hits <n> | time <segundos>]
 */
@Mod(modid = "hitparticles", name = "HitParticles", version = "1.0", clientSideOnly = true)
public class HitParticlesMod {

    // ==================== CONFIGURACOES ====================
    public static boolean enabled = true;
    public static int hitsBeforeHide = 5;      // quantos hits mostram particula antes de sumir
    public static long hideMs = 3000L;         // quanto tempo ficam sumidas
    private static final long RESET_MS = 3000L; // sem bater por esse tempo zera a contagem

    // ==================== ESTADO ====================
    private static int hitCount = 0;
    private static long lastHitMs = 0L;
    private static long hiddenUntil = 0L;

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean installed = false;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        loadSettings();
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new HitCommand());
    }

    // Troca o EffectRenderer do jogo por uma versao que filtra so as particulas de hit.
    // (feito 1x, no primeiro tick, quando o jogo ja terminou de carregar)
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || installed) return;
        if (mc.effectRenderer == null) return;
        mc.effectRenderer = new HitEffectRenderer(mc.theWorld, mc.getTextureManager());
        installed = true;
    }

    // Conta os SEUS hits. O evento e disparado antes das particulas serem criadas,
    // entao o hit que ativa o "sumir" ja nasce sem particula.
    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (!enabled || event.entityPlayer != mc.thePlayer) return;

        long now = System.currentTimeMillis();
        if (now < hiddenUntil) return;                 // ja esta no periodo sumido
        if (hiddenUntil != 0L) {                       // o periodo sumido acabou: recomeça
            hiddenUntil = 0L;
            hitCount = 0;
        }
        if (now - lastHitMs > RESET_MS) hitCount = 0;  // ficou um tempo sem bater
        lastHitMs = now;

        if (hitCount >= hitsBeforeHide) {
            hiddenUntil = now + hideMs;                // some
            hitCount = 0;
        } else {
            hitCount++;
        }
    }

    // ==================== EFFECT RENDERER ====================
    // Critico (CRIT) e sharpness (CRIT_MAGIC) sao criados por emitParticleAtEntity,
    // tanto nos seus hits quanto nos de outros jogadores (pacote do servidor).
    public static class HitEffectRenderer extends EffectRenderer {
        public HitEffectRenderer(World world, TextureManager textures) {
            super(world, textures);
        }

        @Override
        public void emitParticleAtEntity(Entity entity, EnumParticleTypes type) {
            if (enabled
                && (type == EnumParticleTypes.CRIT || type == EnumParticleTypes.CRIT_MAGIC)
                && System.currentTimeMillis() < hiddenUntil) {
                return; // particula escondida
            }
            super.emitParticleAtEntity(entity, type);
        }
    }

    // ==================== CONFIG ====================
    private static File cfgFile;

    private static int parseInt(String v, int min, int max, int def) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (Exception e) {
            return def;
        }
    }

    private static void loadSettings() {
        try {
            File dir = new File(Minecraft.getMinecraft().mcDataDir, "config");
            if (!dir.exists()) dir.mkdirs();
            cfgFile = new File(dir, "hitparticles.properties");
            if (!cfgFile.exists()) {
                saveSettings();
                return;
            }
            Properties p = new Properties();
            try (FileReader r = new FileReader(cfgFile)) {
                p.load(r);
            }
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
            hitsBeforeHide = parseInt(p.getProperty("hits", "5"), 1, 50, 5);
            hideMs = parseInt(p.getProperty("timeMs", "3000"), 500, 30000, 3000);
        } catch (Exception e) {
            System.err.println("[HitParticles] Load failed: " + e.getMessage());
        }
    }

    private static void saveSettings() {
        if (cfgFile == null) return;
        try (FileWriter w = new FileWriter(cfgFile)) {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("hits", String.valueOf(hitsBeforeHide));
            p.setProperty("timeMs", String.valueOf(hideMs));
            p.store(w, "HitParticles Config");
        } catch (Exception e) {
            // ignora
        }
    }

    // ==================== COMANDO ====================
    public static class HitCommand extends CommandBase {
        @Override public String getCommandName() { return "hitparticles"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/hitparticles [toggle|hits <n>|time <segundos>]"; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        private void say(ICommandSender s, String msg) {
            s.addChatMessage(new ChatComponentText("[HitParticles] " + msg));
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                say(sender, (enabled ? "ON" : "OFF") + " | hits: " + hitsBeforeHide
                    + " | time: " + (hideMs / 1000.0) + "s");
                return;
            }
            String cmd = args[0].toLowerCase();
            if ("toggle".equals(cmd)) {
                enabled = !enabled;
                hiddenUntil = 0L;
                hitCount = 0;
                say(sender, enabled ? "ON" : "OFF");
            } else if ("hits".equals(cmd) && args.length > 1) {
                hitsBeforeHide = parseInt(args[1], 1, 50, hitsBeforeHide);
                say(sender, "hits: " + hitsBeforeHide);
            } else if ("time".equals(cmd) && args.length > 1) {
                try {
                    double sec = Double.parseDouble(args[1].replace(',', '.'));
                    hideMs = (long) Math.max(500, Math.min(30000, sec * 1000.0));
                    say(sender, "time: " + (hideMs / 1000.0) + "s");
                } catch (NumberFormatException e) {
                    say(sender, getCommandUsage(sender));
                    return;
                }
            } else {
                say(sender, getCommandUsage(sender));
                return;
            }
            saveSettings();
        }
    }
}
