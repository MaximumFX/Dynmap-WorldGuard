package org.dynmap.worldguard;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.domains.PlayerDomain;
import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionType;
import com.sk89q.worldguard.util.profile.cache.ProfileCache;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DynmapWorldGuardPlugin extends JavaPlugin {
    private static Logger log;
    private static final String DEF_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> Owner <span style=\"font-weight:bold;\">%playerowners%</span><br />Flags<br /><span style=\"font-weight:bold;\">%flags%</span></div>";
    private static final String BOOST_FLAG = "dynmap-boost";
    private Plugin dynmap;
    private DynmapAPI api;
	private BooleanFlag boost_flag;
    private int updatesPerTick = 20;

	private MarkerSet set;
    private long updPeriod;
    private boolean use3d;
    private String infoWindow;
    private AreaStyle defStyle;
    private Map<String, AreaStyle> cusStyle;
    private Map<String, AreaStyle> cusWildStyle;
    private Map<String, AreaStyle> ownerStyle;
    private Set<String> visible;
    private Set<String> hidden;
    private boolean stop;
    private int maxDepth;

    @Override
    public void onLoad() {
        log = this.getLogger();
        this.registerCustomFlags();
    }
    
    private static class AreaStyle {
        String strokeColor;
        String unownedStrokeColor;
        double strokeOpacity;
        int strokeWeight;
        String fillColor;
        double fillOpacity;
        String label;

        AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
            strokeColor = cfg.getString(path+".strokeColor", def.strokeColor);
            unownedStrokeColor = cfg.getString(path+".unownedStrokeColor", def.unownedStrokeColor);
            strokeOpacity = cfg.getDouble(path+".strokeOpacity", def.strokeOpacity);
            strokeWeight = cfg.getInt(path+".strokeWeight", def.strokeWeight);
            fillColor = cfg.getString(path+".fillColor", def.fillColor);
            fillOpacity = cfg.getDouble(path+".fillOpacity", def.fillOpacity);
            label = cfg.getString(path+".label", null);
        }

        AreaStyle(FileConfiguration cfg, String path) {
            strokeColor = cfg.getString(path+".strokeColor", "#FF0000");
            unownedStrokeColor = cfg.getString(path+".unownedStrokeColor", "#00FF00");
            strokeOpacity = cfg.getDouble(path+".strokeOpacity", 0.8);
            strokeWeight = cfg.getInt(path+".strokeWeight", 3);
            fillColor = cfg.getString(path+".fillColor", "#FF0000");
            fillOpacity = cfg.getDouble(path+".fillOpacity", 0.35);
        }
    }
    
    private static void info(String msg) {
        log.log(Level.INFO, msg);
    }
    private static void severe(String msg) {
        log.log(Level.SEVERE, msg);
    }
    
    private Map<String, AreaMarker> resAreas = new HashMap<>();

    private String formatInfoWindow(ProtectedRegion region, AreaMarker m) {
        String v = "<div class=\"regioninfo\">"+ infoWindow +"</div>";
        ProfileCache pc = WorldGuard.getInstance().getProfileCache();
        v = v.replace("%regionname%", m.getLabel());
        v = v.replace("%playerowners%", region.getOwners().toPlayersString(pc));
        v = v.replace("%groupowners%", region.getOwners().toGroupsString());
        v = v.replace("%playermembers%", region.getMembers().toPlayersString(pc));
        v = v.replace("%groupmembers%", region.getMembers().toGroupsString());
        if(region.getParent() != null)
            v = v.replace("%parent%", region.getParent().getId());
        else
            v = v.replace("%parent%", "");
        v = v.replace("%priority%", String.valueOf(region.getPriority()));
        Map<Flag<?>, Object> map = region.getFlags();
        String flags = "";
        for(Flag<?> f : map.keySet()) {
            flags += f.getName() + ": " + map.get(f).toString() + "<br/>";
        }
        v = v.replace("%flags%", flags);
        return v;
    }
    
    private boolean isVisible(String id, String worldName) {
        if((visible != null) && (visible.size() > 0)) {
            if((!visible.contains(id)) && (!visible.contains("world:" + worldName)) &&
                    (!visible.contains(worldName + "/" + id))) {
                return false;
            }
        }
        if((hidden != null) && (hidden.size() > 0)) {
			return !hidden.contains(id) && !hidden.contains("world:" + worldName) && !hidden.contains(worldName + "/" + id);
        }
        return true;
    }
    
    private void addStyle(String resId, String worldId, AreaMarker m, ProtectedRegion region) {
        AreaStyle as = cusStyle.get(worldId + "/" + resId);
        if(as == null) {
            as = cusStyle.get(resId);
        }
        if(as == null) {    /* Check for wildcard style matches */
            for(String wc : cusWildStyle.keySet()) {
                String[] tok = wc.split("\\|");
                if((tok.length == 1) && resId.startsWith(tok[0]))
                    as = cusWildStyle.get(wc);
                else if((tok.length >= 2) && resId.startsWith(tok[0]) && resId.endsWith(tok[1]))
                    as = cusWildStyle.get(wc);
            }
        }
        if(as == null) {    /* Check for owner style matches */
            if(!ownerStyle.isEmpty()) {
                DefaultDomain dd = region.getOwners();
                PlayerDomain pd = dd.getPlayerDomain();
                if(pd != null) {
                    for(String p : pd.getPlayers()) {
						as = ownerStyle.get(p.toLowerCase());
						if (as != null) break;
					}
                    if (as == null) {
                        for(UUID uuid : pd.getUniqueIds()) {
                            as = ownerStyle.get(uuid.toString());
                            if (as != null) break;
                        }
                    }
                    if (as == null) {
                    	for(String p : pd.getPlayers()) {
                            if (p != null) {
                                as = ownerStyle.get(p.toLowerCase());
                                if (as != null) break;
                            }
                        }
                    }
                }
                if (as == null) {
                    Set<String> grp = dd.getGroups();
                    if(grp != null) {
                        for(String p : grp) {
                            as = ownerStyle.get(p.toLowerCase());
                            if (as != null) break;
                        }
                    }
                }
            }
        }
        if(as == null)
            as = defStyle;

        boolean unowned = false;
        if((region.getOwners().getPlayers().size() == 0) &&
                (region.getOwners().getUniqueIds().size() == 0 )&&
                (region.getOwners().getGroups().size() == 0)) {
            unowned = true;
        }
        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            if(unowned)
                sc = Integer.parseInt(as.unownedStrokeColor.substring(1), 16);
            else
                sc = Integer.parseInt(as.strokeColor.substring(1), 16);
           fc = Integer.parseInt(as.fillColor.substring(1), 16);
        } catch (NumberFormatException ignored) {
        }
        m.setLineStyle(as.strokeWeight, as.strokeOpacity, sc);
        m.setFillStyle(as.fillOpacity, fc);
        if(as.label != null) {
            m.setLabel(as.label);
        }
        if (boost_flag != null) {
            Boolean b = region.getFlag(boost_flag);
            m.setBoostFlag(b != null && b);
        }
    }
        
    /* Handle specific region */
    private void handleRegion(World world, ProtectedRegion region, Map<String, AreaMarker> newMap) {
        String name = region.getId();
        /* Make first letter uppercase */
        name = name.substring(0, 1).toUpperCase() + name.substring(1);
        double[] x;
        double[] z;
                
        /* Handle areas */
        if(isVisible(region.getId(), world.getName())) {
            String id = region.getId();
            RegionType tn = region.getType();
            BlockVector3 l0 = region.getMinimumPoint();
            BlockVector3 l1 = region.getMaximumPoint();

            if(tn == RegionType.CUBOID) { /* Cubiod region? */
                /* Make outline */
                x = new double[4];
                z = new double[4];
                x[0] = l0.getX(); z[0] = l0.getZ();
                x[1] = l0.getX(); z[1] = l1.getZ()+1.0;
                x[2] = l1.getX() + 1.0; z[2] = l1.getZ()+1.0;
                x[3] = l1.getX() + 1.0; z[3] = l0.getZ();
            }
            else if(tn == RegionType.POLYGON) {
                ProtectedPolygonalRegion ppr = (ProtectedPolygonalRegion)region;
                List<BlockVector2> points = ppr.getPoints();
                x = new double[points.size()];
                z = new double[points.size()];
                for(int i = 0; i < points.size(); i++) {
                    BlockVector2 pt = points.get(i);
                    x[i] = pt.getX(); z[i] = pt.getZ();
                }
            }
            else {  /* Unsupported type */
                return;
            }
            String markerId = world.getName() + "_" + id;
            AreaMarker m = resAreas.remove(markerId); /* Existing area? */
            if(m == null) {
                m = set.createAreaMarker(markerId, name, false, world.getName(), x, z, false);
                if(m == null)
                    return;
            }
            else {
                m.setCornerLocations(x, z); /* Replace corner locations */
                m.setLabel(name);   /* Update label */
            }
            if(use3d) { /* If 3D? */
                m.setRangeY(l1.getY()+1.0, l0.getY());
            }            
            /* Set line and fill properties */
            addStyle(id, world.getName(), m, region);

            /* Build popup */
            String desc = formatInfoWindow(region, m);

            m.setDescription(desc); /* Set popup */

            /* Add to map */
            newMap.put(markerId, m);
        }
    }
    
    private class UpdateJob implements Runnable {
        Map<String,AreaMarker> newMap = new HashMap<>(); /* Build new map */
        List<World> worldsToDo = null;
        List<ProtectedRegion> regionsToDo = null;
        World curWorld = null;
        
        public void run() {
            if (stop) {
                return;
            }
            // If worlds list isn't primed, prime it
            if (worldsToDo == null) {
            	List<org.bukkit.World> worlds = Bukkit.getWorlds();
                worldsToDo = new ArrayList<>();
                for (org.bukkit.World world : worlds) {
                	worldsToDo.add(WorldGuard.getInstance().getPlatform().getMatcher().getWorldByName(world.getName()));
                }
            }
            while (regionsToDo == null) {  // No pending regions for world
                if (worldsToDo.isEmpty()) { // No more worlds?
                    /* Now, review old map - anything left is gone */
                    for(AreaMarker oldM : resAreas.values()) {
                        oldM.deleteMarker();
                    }
                    /* And replace with new map */
                    resAreas = newMap;
                    // Set up for next update (new job)
                    getServer().getScheduler().scheduleSyncDelayedTask(DynmapWorldGuardPlugin.this, new UpdateJob(), updPeriod);
                    return;
                }
                else {
                    curWorld = worldsToDo.remove(0);
                    RegionContainer rc = WorldGuard.getInstance().getPlatform().getRegionContainer();
                    RegionManager rm = rc.get(curWorld); /* Get region manager for world */
                    if(rm != null) {
                        Map<String,ProtectedRegion> regions = rm.getRegions();  /* Get all the regions */
                        if (!regions.isEmpty()) {
                            regionsToDo = new ArrayList<>(regions.values());
                        }
                    }
                }
            }
            /* Now, process up to limit regions */
            for (int i = 0; i < updatesPerTick; i++) {
                if (regionsToDo.isEmpty()) {
                    regionsToDo = null;
                    break;
                }
                ProtectedRegion pr = regionsToDo.remove(regionsToDo.size()-1);
                int depth = 1;
                ProtectedRegion p = pr;
                while(p.getParent() != null) {
                    depth++;
                    p = p.getParent();
                }
                if(depth > maxDepth)
                    continue;
                handleRegion(curWorld, pr, newMap);
            }
            // Tick next step in the job
            getServer().getScheduler().scheduleSyncDelayedTask(DynmapWorldGuardPlugin.this, this, 1);
        }
    }

    private class OurServerListener implements Listener {
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap")) {
                Plugin wg = p.getServer().getPluginManager().getPlugin("WorldGuard");
                if(wg != null && wg.isEnabled())
                    activate();
            } else if(name.equals("WorldGuard") && dynmap.isEnabled()) {   
                activate();
            }
        }
    }
    
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */
        /* Get WorldGuard */
        Plugin wgp = pm.getPlugin("WorldGuard");
        if(wgp == null) {
            severe("Cannot find WorldGuard!");
            return;
        }
        
        getServer().getPluginManager().registerEvents(new OurServerListener(), this);        
        
        /* If both enabled, activate */
        if(dynmap.isEnabled() && wgp.isEnabled())
            activate();
        /* Start up metrics */
        try {
            MetricsLite ml = new MetricsLite(this);
            ml.start();
        } catch (IOException ignored) {
            
        }
    }
    
    private void registerCustomFlags() {
        try {
            BooleanFlag bf = new BooleanFlag(BOOST_FLAG);
            FlagRegistry fr = WorldGuard.getInstance().getFlagRegistry();
        	fr.register(bf);
            boost_flag = bf;
        } catch (Exception x) {
        	log.info("Error registering flag - " + x.getMessage());
        }
        if (boost_flag == null) {
            log.info("Custom flag '" + BOOST_FLAG + "' not registered");
        }
    }
    
    private boolean reload = false;
    
    private void activate() {        
        /* Now, get markers API */
		MarkerAPI markerAPI = api.getMarkerAPI();
        if(markerAPI == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        /* Load configuration */
        if(reload) {
            this.reloadConfig();
        }
        else {
            reload = true;
        }
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Now, add marker set for mobs (make it transient) */
        set = markerAPI.getMarkerSet("worldguard.markerset");
        if(set == null)
            set = markerAPI.createMarkerSet("worldguard.markerset", cfg.getString("layer.name", "WorldGuard"), null, false);
        else
            set.setMarkerSetLabel(cfg.getString("layer.name", "WorldGuard"));
        if(set == null) {
            severe("Error creating marker set");
            return;
        }
        int minZoom = cfg.getInt("layer.minzoom", 0);
        if(minZoom > 0)
            set.setMinZoom(minZoom);
        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
        use3d = cfg.getBoolean("use3dregions", false);
        infoWindow = cfg.getString("infowindow", DEF_INFOWINDOW);
        maxDepth = cfg.getInt("maxdepth", 16);
        updatesPerTick = cfg.getInt("updates-per-tick", 20);

        /* Get style information */
        defStyle = new AreaStyle(cfg, "regionstyle");
        cusStyle = new HashMap<>();
        ownerStyle = new HashMap<>();
        cusWildStyle = new HashMap<>();
        ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);
            
            for(String id : ids) {
                if(id.indexOf('|') >= 0)
                    cusWildStyle.put(id, new AreaStyle(cfg, "custstyle." + id, defStyle));
                else
                    cusStyle.put(id, new AreaStyle(cfg, "custstyle." + id, defStyle));
            }
        }
        sect = cfg.getConfigurationSection("ownerstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);
            
            for(String id : ids) {
                ownerStyle.put(id.toLowerCase(), new AreaStyle(cfg, "ownerstyle." + id, defStyle));
            }
        }
        List<String> vis = cfg.getStringList("visibleregions");
        if(vis != null) {
            visible = new HashSet<>(vis);
        }
        List<String> hid = cfg.getStringList("hiddenregions");
        if(hid != null) {
            hidden = new HashSet<>(hid);
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 300);
        if(per < 15) per = 15;
        updPeriod = per*20;
        stop = false;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new UpdateJob(), 40);   /* First time is 2 seconds */
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resAreas.clear();
        stop = true;
    }

}
