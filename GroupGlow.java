package dev.groups;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitiesLoadEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;
import java.util.regex.Pattern;

public final class GroupGlow extends JavaPlugin implements Listener {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,16}");
    private static final Set<String> RESERVED = Set.of("a", "e", "p", "r", "s", "n");
    private static final int MAX_EXPAND = 200;

    private Scoreboard board;
    private boolean glowOn;
    /** lowercase name -> Team */
    private final Map<String, Team> groups = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        board = Bukkit.getScoreboardManager().getMainScoreboard();
        loadState();
        Bukkit.getPluginManager().registerEvents(this, this);
        applyGlowToAll();
    }

    @Override
    public void onDisable() {
        saveState();
    }

    // ------------------------------------------------------------ persistence

    private static String teamId(String name) {
        return "gg_" + name.toLowerCase(Locale.ROOT);
    }

    private void loadState() {
        reloadConfig();
        glowOn = getConfig().getBoolean("glow", false);
        groups.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("groups");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection g = sec.getConfigurationSection(key);
            if (g == null) continue;
            String display = g.getString("name", key);
            NamedTextColor color = NamedTextColor.NAMES.value(g.getString("color", "white"));
            Team t = board.getTeam(teamId(display));
            if (t == null) t = board.registerNewTeam(teamId(display));
            t.color(color == null ? NamedTextColor.WHITE : color);
            t.displayName(Component.text(display));
            for (String entry : g.getStringList("members")) t.addEntry(entry);
            groups.put(display.toLowerCase(Locale.ROOT), t);
        }
    }

    private void saveState() {
        getConfig().set("glow", glowOn);
        getConfig().set("groups", null);
        for (Team t : groups.values()) {
            String display = displayName(t);
            String path = "groups." + display.toLowerCase(Locale.ROOT);
            getConfig().set(path + ".name", display);
            NamedTextColor c = t.color() instanceof NamedTextColor n ? n : NamedTextColor.WHITE;
            getConfig().set(path + ".color", NamedTextColor.NAMES.key(c));
            getConfig().set(path + ".members", new ArrayList<>(t.getEntries()));
        }
        saveConfig();
    }

    private static String displayName(Team t) {
        return t.getName().substring(3);
    }

    // ------------------------------------------------------------ helpers

    private static String entryOf(Entity e) {
        return e instanceof Player p ? p.getName() : e.getUniqueId().toString();
    }

    private static Entity entityOf(String entry) {
        try {
            return Bukkit.getEntity(UUID.fromString(entry));
        } catch (IllegalArgumentException ex) {
            return Bukkit.getPlayerExact(entry);
        }
    }

    private Team group(String name) {
        return groups.get(name.toLowerCase(Locale.ROOT));
    }

    private void applyGlow(Entity e, boolean on) {
        // Entity scheduler keeps this safe on Folia too.
        e.getScheduler().run(this, t -> e.setGlowing(on), null);
    }

    private void applyGlowToAll() {
        for (Team t : groups.values())
            for (String entry : t.getEntries()) {
                Entity e = entityOf(entry);
                if (e != null) applyGlow(e, glowOn);
            }
    }

    private static void msg(CommandSender s, String text) {
        s.sendMessage(Component.text(text));
    }

    /** Resolves an argument (player name, vanilla selector, or @groupname) to entities. */
    private List<Entity> resolve(CommandSender sender, String arg) {
        if (arg.startsWith("@")) {
            Team g = group(arg.substring(1));
            if (g != null) {
                List<Entity> out = new ArrayList<>();
                for (String entry : g.getEntries()) {
                    Entity e = entityOf(entry);
                    if (e != null) out.add(e);
                }
                return out;
            }
            try {
                return new ArrayList<>(Bukkit.selectEntities(sender, arg));
            } catch (IllegalArgumentException ex) {
                return List.of();
            }
        }
        Entity e = entityOf(arg);
        return e == null ? List.of() : List.of(e);
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("groupglow.admin")) {
            msg(sender, "You don't have permission to do that.");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("seegroupglow")) return seeGlow(sender, args);

        if (args.length == 0) {
            msg(sender, "Usage: /group <create|delete|add|remove|list>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "add" -> add(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            default -> msg(sender, "Usage: /group <create|delete|add|remove|list>");
        }
        return true;
    }

    private boolean seeGlow(CommandSender sender, String[] args) {
        if (args.length != 1 || !(args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("false"))) {
            msg(sender, "Usage: /seegroupglow <true|false>  (currently " + glowOn + ")");
            return true;
        }
        glowOn = Boolean.parseBoolean(args[0]);
        saveState();
        // Glow-off must clear every member, so apply to all.
        applyGlowToAll();
        msg(sender, "Group glow outlines are now " + (glowOn ? "ON" : "OFF") + " for everyone.");
        return true;
    }

    private void create(CommandSender sender, String[] args) {
        if (args.length != 3) {
            msg(sender, "Usage: /group create <name> <color>");
            return;
        }
        String name = args[1];
        if (!NAME.matcher(name).matches()) {
            msg(sender, "Group names can only use letters, numbers, _ and -, up to 16 characters.");
            return;
        }
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            msg(sender, "'" + name + "' is a vanilla selector letter and can't be used as a group name.");
            return;
        }
        if (group(name) != null) {
            msg(sender, "A group named '" + name + "' already exists.");
            return;
        }
        NamedTextColor color = NamedTextColor.NAMES.value(args[2].toLowerCase(Locale.ROOT));
        if (color == null) {
            msg(sender, "Unknown color. Use: " + String.join(", ", NamedTextColor.NAMES.keys()));
            return;
        }
        Team old = board.getTeam(teamId(name));
        if (old != null) old.unregister();
        Team t = board.registerNewTeam(teamId(name));
        t.color(color);
        t.displayName(Component.text(name));
        groups.put(name.toLowerCase(Locale.ROOT), t);
        saveState();
        msg(sender, "Created group '" + name + "' (" + args[2].toLowerCase(Locale.ROOT) + "). Use it as @" + name + ".");
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            msg(sender, "Usage: /group delete <name>");
            return;
        }
        Team t = group(args[1]);
        if (t == null) {
            msg(sender, "No group named '" + args[1] + "'.");
            return;
        }
        for (String entry : new ArrayList<>(t.getEntries())) {
            Entity e = entityOf(entry);
            if (e != null) applyGlow(e, false);
        }
        groups.remove(displayName(t).toLowerCase(Locale.ROOT));
        t.unregister();
        saveState();
        msg(sender, "Deleted group '" + args[1] + "'. @" + args[1] + " no longer exists.");
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length != 3) {
            msg(sender, "Usage: /group add <@player|@a|@e...> <group>");
            return;
        }
        Team t = group(args[2]);
        if (t == null) {
            msg(sender, "No group named '" + args[2] + "'.");
            return;
        }
        List<Entity> targets = resolve(sender, args[1]);
        if (targets.isEmpty()) {
            msg(sender, "No matching targets.");
            return;
        }
        for (Entity e : targets) {
            Team prev = board.getEntryTeam(entryOf(e));
            if (prev != null) prev.removeEntry(entryOf(e));
            t.addEntry(entryOf(e));
            applyGlow(e, glowOn);
        }
        saveState();
        msg(sender, "Added " + targets.size() + " target(s) to '" + displayName(t) + "'.");
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length != 3) {
            msg(sender, "Usage: /group remove <@player|@a|@e...> <group>");
            return;
        }
        Team t = group(args[2]);
        if (t == null) {
            msg(sender, "No group named '" + args[2] + "'.");
            return;
        }
        int count = 0;
        List<Entity> targets = resolve(sender, args[1]);
        for (Entity e : targets) {
            if (t.removeEntry(entryOf(e))) {
                applyGlow(e, false);
                count++;
            }
        }
        // Also allow removing offline players by plain name.
        if (targets.isEmpty() && !args[1].startsWith("@") && t.removeEntry(args[1])) count++;
        saveState();
        msg(sender, count == 0 ? "Nobody was removed." : "Removed " + count + " target(s) from '" + displayName(t) + "'.");
    }

    private void list(CommandSender sender) {
        if (groups.isEmpty()) {
            msg(sender, "No groups yet. Create one with /group create <name> <color>.");
            return;
        }
        for (Team t : groups.values()) {
            NamedTextColor c = t.color() instanceof NamedTextColor n ? n : NamedTextColor.WHITE;
            msg(sender, "@" + displayName(t) + " (" + NamedTextColor.NAMES.key(c) + ") - " + t.getEntries().size() + " member(s)");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("groupglow.admin")) return List.of();
        if (cmd.getName().equalsIgnoreCase("seegroupglow"))
            return args.length == 1 ? filter(List.of("true", "false"), args[0]) : List.of();

        if (args.length == 1) return filter(List.of("create", "delete", "add", "remove", "list"), args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (sub.equals("create") && args.length == 3) out.addAll(NamedTextColor.NAMES.keys());
        else if (sub.equals("delete") && args.length == 2) out.addAll(groupNames(false));
        else if ((sub.equals("add") || sub.equals("remove")) && args.length == 2) {
            out.addAll(List.of("@a", "@e", "@p", "@r", "@s"));
            out.addAll(groupNames(true));
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if ((sub.equals("add") || sub.equals("remove")) && args.length == 3) out.addAll(groupNames(false));
        return filter(out, args[args.length - 1]);
    }

    private List<String> groupNames(boolean withAt) {
        List<String> l = new ArrayList<>();
        for (Team t : groups.values()) l.add((withAt ? "@" : "") + displayName(t));
        return l;
    }

    private static List<String> filter(Collection<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    // ------------------------------------------------------------ @groupname in ANY command

    private boolean isOwnCommand(String commandLine) {
        String first = commandLine.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (first.startsWith("/")) first = first.substring(1);
        int colon = first.indexOf(':');
        if (colon >= 0) first = first.substring(colon + 1);
        return first.equals("group") || first.equals("seegroupglow");
    }

    /** Returns the index of the first @groupname token, or -1. */
    private int findGroupToken(String[] tokens) {
        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].startsWith("@") && tokens[i].length() > 1 && group(tokens[i].substring(1)) != null) return i;
        }
        return -1;
    }

    private void expandAndRun(CommandSender sender, String commandLine) {
        String[] tokens = commandLine.split(" ", -1);
        int idx = findGroupToken(tokens);
        if (idx < 0) {
            Bukkit.dispatchCommand(sender, commandLine);
            return;
        }
        Team t = group(tokens[idx].substring(1));
        List<String> names = new ArrayList<>();
        for (String entry : t.getEntries()) {
            Entity e = entityOf(entry);
            if (e != null) names.add(e instanceof Player p ? p.getName() : e.getUniqueId().toString());
        }
        if (names.isEmpty()) {
            msg(sender, "Group '" + displayName(t) + "' has no online/loaded members.");
            return;
        }
        if (names.size() > MAX_EXPAND) names = names.subList(0, MAX_EXPAND);
        for (String n : names) {
            String[] copy = tokens.clone();
            copy[idx] = n;
            expandAndRun(sender, String.join(" ", copy));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        String line = e.getMessage().substring(1);
        if (isOwnCommand(line) || findGroupToken(line.split(" ", -1)) < 0) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> expandAndRun(p, line));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        String line = e.getCommand();
        if (line.startsWith("/")) line = line.substring(1);
        if (isOwnCommand(line) || findGroupToken(line.split(" ", -1)) < 0) return;
        e.setCancelled(true);
        final String fl = line;
        CommandSender s = e.getSender();
        Bukkit.getScheduler().runTask(this, () -> expandAndRun(s, fl));
    }

    @EventHandler
    public void onTabComplete(AsyncTabCompleteEvent e) {
        if (!e.isCommand() || !e.getSender().hasPermission("groupglow.admin")) return;
        String buf = e.getBuffer();
        int sp = buf.lastIndexOf(' ');
        if (sp < 0) return;
        String last = buf.substring(sp + 1);
        if (!last.startsWith("@")) return;
        List<AsyncTabCompleteEvent.Completion> list = new ArrayList<>(e.completions());
        for (String g : groupNames(true))
            if (g.toLowerCase(Locale.ROOT).startsWith(last.toLowerCase(Locale.ROOT)))
                list.add(AsyncTabCompleteEvent.Completion.completion(g));
        e.completions(list);
        e.setHandled(true);
    }

    // ------------------------------------------------------------ glow upkeep

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Team t = board.getEntryTeam(e.getPlayer().getName());
        if (t != null && groups.containsValue(t)) applyGlow(e.getPlayer(), glowOn);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            Team t = board.getEntryTeam(en.getUniqueId().toString());
            if (t != null && groups.containsValue(t)) applyGlow(en, glowOn);
        }
    }
}
