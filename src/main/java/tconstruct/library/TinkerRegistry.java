package tconstruct.library;

import com.google.common.base.CharMatcher;

import gnu.trove.map.hash.THashMap;
import gnu.trove.set.hash.THashSet;
import gnu.trove.set.hash.TLinkedHashSet;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.Event;

import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import tconstruct.library.events.MaterialEvent;
import tconstruct.library.materials.IMaterialStats;
import tconstruct.library.materials.Material;
import tconstruct.library.modifiers.IModifier;
import tconstruct.library.tools.ToolCore;
import tconstruct.library.traits.ITrait;

public final class TinkerRegistry {

  // the logger for the library
  public static final Logger log = Util.getLogger("API");

  private TinkerRegistry() {
  }


  /*---------------------------------------------------------------------------
  | MATERIALS                                                                 |
  ---------------------------------------------------------------------------*/

  // Identifier to Material mapping. Hashmap so we can look it up directly without iterating
  private static final Map<String, Material> materials = new THashMap<>();
  private static final Map<String, ITrait> traits = new THashMap<>();
  // traceability information who registered what. Used to find errors.
  private static final Map<String, String> materialRegisteredByMod = new THashMap<>();
  private static final Map<String, Map<String, String>> statRegisteredByMod = new THashMap<>();
  private static final Map<String, Map<String, String>> traitRegisteredByMod = new THashMap<>();

  private static final Set<String> cancelledMaterials = new THashSet<>(); // contains all cancelled materials, allows us to eat calls regarding the material silently

  public static void addMaterial(Material material, IMaterialStats stats, ITrait trait) {
    addMaterial(material, stats);
    addMaterialTrait(material.identifier, trait);
  }

  public static void addMaterial(Material material, ITrait trait) {
    addMaterial(material);
    addMaterialTrait(material.identifier, trait);
  }

  public static void addMaterial(Material material, IMaterialStats stats) {
    addMaterial(material);
    addMaterialStats(material.identifier, stats);
  }

  /**
   * Registers a material. The materials identifier has to be lowercase and not contain any spaces.
   * Identifiers have to be globally unique!
   */
  public static void addMaterial(Material material) {
    // ensure material identifiers are safe
    if(CharMatcher.WHITESPACE.matchesAnyOf(material.getIdentifier())) {
      error("Could not register Material \"%s\": Material identifier must not contain any spaces.", material.identifier);
      return;
    }
    if(CharMatcher.JAVA_UPPER_CASE.matchesAnyOf(material.getIdentifier())) {
      error("Could not register Material \"%s\": Material identifier must be completely lowercase.", material.identifier);
      return;
    }

    // duplicate material
    if(materials.containsKey(material.identifier)) {
      String registeredBy = materialRegisteredByMod.get(material.identifier);
      error(String.format(
          "Could not register Material \"%s\": It was already registered by %s",
          material.identifier,
          registeredBy));
      return;
    }

    MaterialEvent.MaterialRegisterEvent event = new MaterialEvent.MaterialRegisterEvent(material);

    if(MinecraftForge.EVENT_BUS.post(event)) {
      // event cancelled
      log.trace("Addition of material {} cancelled by event", material.getIdentifier());
      cancelledMaterials.add(material.getIdentifier());
      return;
    }

    // register material
    materials.put(material.identifier, material);
    String activeMod = Loader.instance().activeModContainer().getModId();
    putMaterialTrace(material.identifier, activeMod);
  }

  public static Material getMaterial(String identifier) {
    return materials.containsKey(identifier) ? materials.get(identifier) : Material.UNKNOWN;
  }

  public static Collection<Material> getAllMaterials() {
    return materials.values();
  }


  /*---------------------------------------------------------------------------
  | TRAITS & STATS                                                            |
  ---------------------------------------------------------------------------*/

  public static void addTrait(ITrait trait) {
    // Trait might already have been registered since modifiers and materials share traits
    if(traits.containsKey(trait.getIdentifier())) {
      return;
    }

    traits.put(trait.getIdentifier(), trait);

    String activeMod = Loader.instance().activeModContainer().getModId();
    putTraitTrace(trait.getIdentifier(), trait, activeMod);
  }

  public static void addMaterialStats(String materialIdentifier, IMaterialStats stats) {
    if(cancelledMaterials.contains(materialIdentifier)) return;
    if(!materials.containsKey(materialIdentifier)) {
      error(String.format("Could not add Stats \"%s\" to \"%s\": Unknown Material", stats.getIdentifier(),
                          materialIdentifier));
      return;
    }

    Material material = materials.get(materialIdentifier);
    addMaterialStats(material, stats);
  }

  public static void addMaterialStats(Material material, IMaterialStats stats) {
    if(material == null) {
      error(String.format("Could not add Stats \"%s\": Material is null", stats.getIdentifier()));
      return;
    }
    if(cancelledMaterials.contains(material.identifier)) return;

    String identifier = material.identifier;
    // duplicate stats
    if(material.getStats(stats.getIdentifier()) != null) {
      String registeredBy = "Unknown";
      Map<String, String> matReg = statRegisteredByMod.get(identifier);
      if(matReg != null) {
        registeredBy = matReg.get(stats.getIdentifier());
      }

      error(String.format(
          "Could not add Stats to \"%s\": Stats of type \"%s\" were already registered by %s",
          identifier, stats.getIdentifier(), registeredBy));
      return;
    }

    // ensure there are default stats present
    if(Material.UNKNOWN.getStats(stats.getIdentifier()) == null) {
      error("Could not add Stat of type \"%s\": Default Material does not have default stats for said type. Please add default-values to the default material \"unknown\" first.", stats.getIdentifier());
      return;
    }

    MaterialEvent.StatRegisterEvent<?> event = new MaterialEvent.StatRegisterEvent<>(material, stats);
    MinecraftForge.EVENT_BUS.post(event);

    // overridden stats from event
    if(event.getResult() == Event.Result.ALLOW) {
      stats = event.newStats;
    }


    material.addStats(stats);

    String activeMod = Loader.instance().activeModContainer().getModId();
    putStatTrace(identifier, stats, activeMod);
  }

  public static void addMaterialTrait(String materialIdentifier, ITrait trait) {
    if(cancelledMaterials.contains(materialIdentifier)) return;
    if(!materials.containsKey(materialIdentifier)) {
      error(String.format("Could not add Trait \"%s\" to \"%s\": Unknown Material",
                          trait.getIdentifier(), materialIdentifier));
      return;
    }

    Material material = materials.get(materialIdentifier);
    addMaterialTrait(material, trait);
  }

  public static void addMaterialTrait(Material material, ITrait trait) {
    if(material == null) {
      error(String.format("Could not add Trait \"%s\": Material is null", trait.getIdentifier()));
      return;
    }
    if(cancelledMaterials.contains(material.identifier)) return;

    String identifier = material.identifier;
    // duplicate traits
    if(material.hasTrait(trait.getIdentifier())) {
      String registeredBy = "Unknown";
      Map<String, String> matReg = traitRegisteredByMod.get(identifier);
      if(matReg != null) {
        registeredBy = matReg.get(trait.getIdentifier());
      }

      error(String.format(
          "Could not add Trait to \"%s\": Trait \"%s\" was already registered by %s",
          identifier, trait.getIdentifier(), registeredBy));
      return;
    }

    MaterialEvent.TraitRegisterEvent<?> event = new MaterialEvent.TraitRegisterEvent<>(material, trait);
    if(MinecraftForge.EVENT_BUS.post(event)) {
      // cancelled
      log.trace("Trait {} on {} cancelled by event", trait.getIdentifier(), material.getIdentifier());
      return;
    }

    addTrait(trait);
    material.addTrait(trait);
  }

  public static ITrait getTrait(String identifier) {
    return traits.get(identifier);
  }

  /*---------------------------------------------------------------------------
  | TOOLS & WEAPONS                                                           |
  ---------------------------------------------------------------------------*/

  /** This set contains all known tools */
  public static final Set<ToolCore> tools = new TLinkedHashSet<>();

  public static void addTool(ToolCore tool) {
    tools.add(tool);
  }


  /*---------------------------------------------------------------------------
  | Modifiers                                                                 |
  ---------------------------------------------------------------------------*/
  public static final Map<String, IModifier> modifiers = new THashMap<>();

  public static void registerModifier(IModifier modifier) {
    modifiers.put(modifier.getIdentifier(), modifier);
  }

  public static IModifier getModifier(String identifier) {
    return modifiers.get(identifier);
  }

  public static Collection<IModifier> getAllModifiers() {
    return modifiers.values();
  }

  /*---------------------------------------------------------------------------
  | Traceability & Internal stuff                                             |
  ---------------------------------------------------------------------------*/

  static void putMaterialTrace(String materialIdentifier, String trace) {
    String activeMod = Loader.instance().activeModContainer().getModId();
    materialRegisteredByMod.put(materialIdentifier, activeMod);
  }

  static void putStatTrace(String materialIdentifier, IMaterialStats stats, String trace) {
    if(!statRegisteredByMod.containsKey(materialIdentifier)) {
      statRegisteredByMod.put(materialIdentifier, new HashMap<String, String>());
    }
    statRegisteredByMod.get(materialIdentifier).put(stats.getIdentifier(), trace);
  }

  static void putTraitTrace(String materialIdentifier, ITrait trait, String trace) {
    if(!traitRegisteredByMod.containsKey(materialIdentifier)) {
      traitRegisteredByMod.put(materialIdentifier, new HashMap<String, String>());
    }
    traitRegisteredByMod.get(materialIdentifier).put(trait.getIdentifier(), trace);
  }

  public static String getTrace(Material material) {
    return materialRegisteredByMod.get(material.identifier);
  }

  private static void error(String message, Object... params) {
    throw new TinkerAPIException(String.format(message, params));
  }
}