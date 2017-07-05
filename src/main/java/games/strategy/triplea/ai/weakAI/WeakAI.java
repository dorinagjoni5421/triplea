package games.strategy.triplea.ai.weakAI;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.TripleAUnit;
import games.strategy.triplea.ai.AIUtils;
import games.strategy.triplea.ai.AbstractAI;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.BattleDelegate;
import games.strategy.triplea.delegate.DelegateFinder;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.dataObjects.PlaceableUnits;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.delegate.remote.ITechDelegate;
import games.strategy.util.IntegerMap;
import games.strategy.util.Match;
import games.strategy.util.Util;

/*
 * A very weak ai, based on some simple rules.<p>
 */
public class WeakAI extends AbstractAI {
  private static final Logger s_logger = Logger.getLogger(WeakAI.class.getName());

  /** Creates new WeakAI. */
  public WeakAI(final String name, final String type) {
    super(name, type);
  }

  @Override
  protected void tech(final ITechDelegate techDelegate, final GameData data, final PlayerID player) {}

  private static Route getAmphibRoute(final PlayerID player, final GameData data) {
    if (!isAmphibAttack(player, data)) {
      return null;
    }
    final Territory ourCapitol = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
    final Match<Territory> endMatch = Match.of(o -> {
      final boolean impassable = TerritoryAttachment.get(o) != null && TerritoryAttachment.get(o).getIsImpassable();
      return !impassable && !o.isWater() && Utils.hasLandRouteToEnemyOwnedCapitol(o, player, data);
    });
    final Match<Territory> routeCond =
        Match.allOf(Matches.TerritoryIsWater, Matches.territoryHasNoEnemyUnits(player, data));
    final Route withNoEnemy = Utils.findNearest(ourCapitol, endMatch, routeCond, data);
    if (withNoEnemy != null && withNoEnemy.numberOfSteps() > 0) {
      return withNoEnemy;
    }
    // this will fail if our capitol is not next to water, c'est la vie.
    final Route route = Utils.findNearest(ourCapitol, endMatch, Matches.TerritoryIsWater, data);
    if (route != null && route.numberOfSteps() == 0) {
      return null;
    }
    return route;
  }

  private static boolean isAmphibAttack(final PlayerID player, final GameData data) {
    final Territory capitol = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
    // we dont own our own capitol
    if (capitol == null || !capitol.getOwner().equals(player)) {
      return false;
    }
    // find a land route to an enemy territory from our capitol
    final Route invasionRoute =
        Utils.findNearest(capitol, Matches.isTerritoryEnemyAndNotUnownedWaterOrImpassableOrRestricted(player, data),
            Match.allOf(Matches.TerritoryIsLand, Matches.TerritoryIsNeutralButNotWater.invert()), data);
    return invasionRoute == null;
  }

  @Override
  protected void move(final boolean nonCombat, final IMoveDelegate moveDel, final GameData data,
      final PlayerID player) {
    if (nonCombat) {
      doNonCombatMove(moveDel, player, data);
    } else {
      doCombatMove(moveDel, player, data);
    }
    pause();
  }

  private void doNonCombatMove(final IMoveDelegate moveDel, final PlayerID player, final GameData data) {
    final List<Collection<Unit>> moveUnits = new ArrayList<>();
    final List<Route> moveRoutes = new ArrayList<>();
    final List<Collection<Unit>> transportsToLoad = new ArrayList<>();
    // load the transports first
    // they may be able to move farther
    populateTransportLoad(data, moveUnits, moveRoutes, transportsToLoad, player);
    doMove(moveUnits, moveRoutes, transportsToLoad, moveDel);
    moveRoutes.clear();
    moveUnits.clear();
    transportsToLoad.clear();
    // do the rest of the moves
    populateNonCombat(data, moveUnits, moveRoutes, player);
    populateNonCombatSea(true, data, moveUnits, moveRoutes, player);
    doMove(moveUnits, moveRoutes, null, moveDel);
    moveUnits.clear();
    moveRoutes.clear();
    transportsToLoad.clear();
    // load the transports again if we can
    // they may be able to move farther
    populateTransportLoad(data, moveUnits, moveRoutes, transportsToLoad, player);
    doMove(moveUnits, moveRoutes, transportsToLoad, moveDel);
    moveRoutes.clear();
    moveUnits.clear();
    transportsToLoad.clear();
    // unload the transports that can be unloaded
    populateTransportUnloadNonCom(data, moveUnits, moveRoutes, player);
    doMove(moveUnits, moveRoutes, null, moveDel);
  }

  private void doCombatMove(final IMoveDelegate moveDel, final PlayerID player, final GameData data) {
    final List<Collection<Unit>> moveUnits = new ArrayList<>();
    final List<Route> moveRoutes = new ArrayList<>();
    final List<Collection<Unit>> transportsToLoad = new ArrayList<>();
    // load the transports first
    // they may be able to take part in a battle
    populateTransportLoad(data, moveUnits, moveRoutes, transportsToLoad, player);
    doMove(moveUnits, moveRoutes, transportsToLoad, moveDel);
    moveRoutes.clear();
    moveUnits.clear();
    // we want to move loaded transports before we try to fight our battles
    populateNonCombatSea(false, data, moveUnits, moveRoutes, player);
    // find second amphib target
    final Route altRoute = getAlternativeAmphibRoute(player, data);
    if (altRoute != null) {
      moveCombatSea(data, moveUnits, moveRoutes, player, altRoute, 1);
    }
    doMove(moveUnits, moveRoutes, null, moveDel);
    moveUnits.clear();
    moveRoutes.clear();
    transportsToLoad.clear();
    // fight
    populateCombatMove(data, moveUnits, moveRoutes, player);
    populateCombatMoveSea(data, moveUnits, moveRoutes, player);
    doMove(moveUnits, moveRoutes, null, moveDel);
  }

  private void populateTransportLoad(final GameData data, final List<Collection<Unit>> moveUnits,
      final List<Route> moveRoutes, final List<Collection<Unit>> transportsToLoad, final PlayerID player) {
    if (!isAmphibAttack(player, data)) {
      return;
    }
    final Territory capitol = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
    if (capitol == null || !capitol.getOwner().equals(player)) {
      return;
    }
    List<Unit> unitsToLoad = capitol.getUnits().getMatches(Matches.UnitIsInfrastructure.invert());
    unitsToLoad = Match.getMatches(unitsToLoad, Matches.unitIsOwnedBy(getPlayerID()));
    for (final Territory neighbor : data.getMap().getNeighbors(capitol)) {
      if (!neighbor.isWater()) {
        continue;
      }
      final List<Unit> units = new ArrayList<>();
      for (final Unit transport : neighbor.getUnits().getMatches(Matches.unitIsOwnedBy(player))) {
        int free = TransportTracker.getAvailableCapacity(transport);
        if (free <= 0) {
          continue;
        }
        final Iterator<Unit> iter = unitsToLoad.iterator();
        while (iter.hasNext() && free > 0) {
          final Unit current = iter.next();
          final UnitAttachment ua = UnitAttachment.get(current.getType());
          if (ua.getIsAir()) {
            continue;
          }
          if (ua.getTransportCost() <= free) {
            iter.remove();
            free -= ua.getTransportCost();
            units.add(current);
          }
        }
      }
      if (units.size() > 0) {
        final Route route = new Route();
        route.setStart(capitol);
        route.add(neighbor);
        moveUnits.add(units);
        moveRoutes.add(route);
        transportsToLoad.add(neighbor.getUnits().getMatches(Matches.UnitIsTransport));
      }
    }
  }

  private static void populateTransportUnloadNonCom(final GameData data, final List<Collection<Unit>> moveUnits,
      final List<Route> moveRoutes, final PlayerID player) {
    final Route amphibRoute = getAmphibRoute(player, data);
    if (amphibRoute == null) {
      return;
    }
    final Territory lastSeaZoneOnAmphib = amphibRoute.getAllTerritories().get(amphibRoute.numberOfSteps() - 1);
    final Territory landOn = amphibRoute.getEnd();
    final Match<Unit> landAndOwned = Match.allOf(Matches.UnitIsLand, Matches.unitIsOwnedBy(player));
    final List<Unit> units = lastSeaZoneOnAmphib.getUnits().getMatches(landAndOwned);
    if (units.size() > 0) {
      // just try to make the move, the engine will stop us if it doesnt work
      final Route route = new Route();
      route.setStart(lastSeaZoneOnAmphib);
      route.add(landOn);
      moveUnits.add(units);
      moveRoutes.add(route);
    }
  }

  private static List<Unit> load2Transports(final List<Unit> transportsToLoad) {
    final List<Unit> units = new ArrayList<>();
    for (final Unit transport : transportsToLoad) {
      final Collection<Unit> landunits = TransportTracker.transporting(transport);
      for (final Unit u : landunits) {
        units.add(u);
      }
    }
    return units;
  }

  private void doMove(final List<Collection<Unit>> moveUnits, final List<Route> moveRoutes,
      final List<Collection<Unit>> transportsToLoad, final IMoveDelegate moveDel) {
    for (int i = 0; i < moveRoutes.size(); i++) {
      pause();
      if (moveRoutes.get(i) == null || moveRoutes.get(i).getEnd() == null || moveRoutes.get(i).getStart() == null
          || moveRoutes.get(i).hasNoSteps()) {
        s_logger.fine("Route not valid" + moveRoutes.get(i) + " units:" + moveUnits.get(i));
        continue;
      }
      final String result;
      if (transportsToLoad == null) {
        result = moveDel.move(moveUnits.get(i), moveRoutes.get(i));
      } else {
        result = moveDel.move(moveUnits.get(i), moveRoutes.get(i), transportsToLoad.get(i));
      }
      if (result != null) {
        s_logger.fine("could not move " + moveUnits.get(i) + " over " + moveRoutes.get(i) + " because : " + result);
      }
    }
  }

  private static void moveCombatSea(final GameData data, final List<Collection<Unit>> moveUnits,
      final List<Route> moveRoutes, final PlayerID player, final Route amphibRoute, final int maxTrans) {
    // TODO workaround - should check if amphibRoute is in moveRoutes
    if (moveRoutes.size() == 2) {
      moveRoutes.remove(1);
      moveUnits.remove(1);
    }
    Territory firstSeaZoneOnAmphib = null;
    Territory lastSeaZoneOnAmphib = null;
    if (amphibRoute == null) {
      return;
    }
    firstSeaZoneOnAmphib = amphibRoute.getAllTerritories().get(0);
    lastSeaZoneOnAmphib = amphibRoute.getAllTerritories().get(amphibRoute.numberOfSteps() - 1);
    final Match<Unit> ownedAndNotMoved =
        Match.allOf(Matches.unitIsOwnedBy(player), Matches.unitHasNotMoved, Transporting);
    final List<Unit> unitsToMove = new ArrayList<>();
    final List<Unit> transports = firstSeaZoneOnAmphib.getUnits().getMatches(ownedAndNotMoved);
    if (transports.size() <= maxTrans) {
      unitsToMove.addAll(transports);
    } else {
      unitsToMove.addAll(transports.subList(0, maxTrans));
    }
    final List<Unit> landUnits = load2Transports(unitsToMove);
    final Route r = getMaxSeaRoute(data, firstSeaZoneOnAmphib, lastSeaZoneOnAmphib, player);
    moveRoutes.add(r);
    unitsToMove.addAll(landUnits);
    moveUnits.add(unitsToMove);
  }

  /**
   * prepares moves for transports.
   *
   * @param maxTrans
   *        -
   *        if -1 unlimited
   */
  private static void populateNonCombatSea(final boolean nonCombat, final GameData data,
      final List<Collection<Unit>> moveUnits, final List<Route> moveRoutes, final PlayerID player) {
    final Route amphibRoute = getAmphibRoute(player, data);
    Territory firstSeaZoneOnAmphib = null;
    Territory lastSeaZoneOnAmphib = null;
    if (amphibRoute != null) {
      firstSeaZoneOnAmphib = amphibRoute.getAllTerritories().get(1);
      lastSeaZoneOnAmphib = amphibRoute.getAllTerritories().get(amphibRoute.numberOfSteps() - 1);
    }
    final Match<Unit> ownedAndNotMoved = Match.allOf(Matches.unitIsOwnedBy(player), Matches.unitHasNotMoved);
    for (final Territory t : data.getMap()) {
      // move sea units to the capitol, unless they are loaded transports
      if (t.isWater()) {
        // land units, move all towards the end point
        if (t.getUnits().someMatch(Matches.UnitIsLand)) {
          // move along amphi route
          if (lastSeaZoneOnAmphib != null) {
            // two move route to end
            final Route r = getMaxSeaRoute(data, t, lastSeaZoneOnAmphib, player);
            if (r != null && r.numberOfSteps() > 0) {
              moveRoutes.add(r);
              final List<Unit> unitsToMove = t.getUnits().getMatches(Matches.unitIsOwnedBy(player));
              moveUnits.add(unitsToMove);
            }
          }
        }
        if (nonCombat && t.getUnits().someMatch(ownedAndNotMoved)) {
          // move toward the start of the amphib route
          if (firstSeaZoneOnAmphib != null) {
            final Route r = getMaxSeaRoute(data, t, firstSeaZoneOnAmphib, player);
            moveRoutes.add(r);
            moveUnits.add(t.getUnits().getMatches(ownedAndNotMoved));
          }
        }
      }
    }
  }

  private static Route getMaxSeaRoute(final GameData data, final Territory start, final Territory destination,
      final PlayerID player) {
    final Match<Territory> routeCond =
        Match.allOf(Matches.TerritoryIsWater, Matches.territoryHasEnemyUnits(player, data).invert(),
            Matches.territoryHasNonAllowedCanal(player, null, data).invert());
    Route r = data.getMap().getRoute(start, destination, routeCond);
    if (r == null) {
      return null;
    }
    if (r.numberOfSteps() > 2) {
      final Route newRoute = new Route();
      newRoute.setStart(start);
      newRoute.add(r.getAllTerritories().get(1));
      newRoute.add(r.getAllTerritories().get(2));
      r = newRoute;
    }
    return r;
  }

  private static void populateCombatMoveSea(final GameData data, final List<Collection<Unit>> moveUnits,
      final List<Route> moveRoutes, final PlayerID player) {
    final Collection<Unit> unitsAlreadyMoved = new HashSet<>();
    for (final Territory t : data.getMap()) {
      if (!t.isWater()) {
        continue;
      }
      if (!t.getUnits().someMatch(Matches.enemyUnit(player, data))) {
        continue;
      }
      final Territory enemy = t;
      final float enemyStrength = AIUtils.strength(enemy.getUnits().getUnits(), false, true);
      if (enemyStrength > 0) {
        final Match<Unit> attackable =
            Match.allOf(Matches.unitIsOwnedBy(player), Match.of(o -> !unitsAlreadyMoved.contains(o)));
        final Set<Territory> dontMoveFrom = new HashSet<>();
        // find our strength that we can attack with
        int ourStrength = 0;
        final Collection<Territory> attackFrom = data.getMap().getNeighbors(enemy, Matches.TerritoryIsWater);
        for (final Territory owned : attackFrom) {
          // dont risk units we are carrying
          if (owned.getUnits().someMatch(Matches.UnitIsLand)) {
            dontMoveFrom.add(owned);
            continue;
          }
          ourStrength += AIUtils.strength(owned.getUnits().getMatches(attackable), true, true);
        }
        if (ourStrength > 1.32 * enemyStrength) {
          s_logger.fine("Attacking : " + enemy + " our strength:" + ourStrength + " enemy strength" + enemyStrength);
          for (final Territory owned : attackFrom) {
            if (dontMoveFrom.contains(owned)) {
              continue;
            }
            final List<Unit> units = owned.getUnits().getMatches(attackable);
            unitsAlreadyMoved.addAll(units);
            moveUnits.add(units);
            moveRoutes.add(data.getMap().getRoute(owned, enemy));
          }
        }
      }
    }
  }

  // searches for amphibious attack on empty territory
  private static Route getAlternativeAmphibRoute(final PlayerID player, final GameData data) {
    if (!isAmphibAttack(player, data)) {
      return null;
    }
    final Match<Territory> routeCondition =
        Match.allOf(Matches.TerritoryIsWater, Matches.territoryHasNoEnemyUnits(player, data));
    // should select all territories with loaded transports
    final Match<Territory> transportOnSea =
        Match.allOf(Matches.TerritoryIsWater, Matches.territoryHasLandUnitsOwnedBy(player));
    Route altRoute = null;
    final int length = Integer.MAX_VALUE;
    for (final Territory t : data.getMap()) {
      if (!transportOnSea.match(t)) {
        continue;
      }
      final Match<Unit> ownedTransports =
          Match.allOf(Matches.UnitCanTransport, Matches.unitIsOwnedBy(player), Matches.unitHasNotMoved);
      final Match<Territory> enemyTerritory =
          Match.allOf(Matches.isTerritoryEnemy(player, data), Matches.TerritoryIsLand,
              Matches.TerritoryIsNeutralButNotWater.invert(), Matches.TerritoryIsEmpty);
      final int trans = t.getUnits().countMatches(ownedTransports);
      if (trans > 0) {
        final Route newRoute = Utils.findNearest(t, enemyTerritory, routeCondition, data);
        if (newRoute != null && length > newRoute.numberOfSteps()) {
          altRoute = newRoute;
        }
      }
    }
    return altRoute;
  }

  private void populateNonCombat(final GameData data, final List<Collection<Unit>> moveUnits,
      final List<Route> moveRoutes, final PlayerID player) {
    final Collection<Territory> territories = data.getMap().getTerritories();
    movePlanesHomeNonCom(moveUnits, moveRoutes, player, data);
    // move our units toward the nearest enemy capitol
    for (final Territory t : territories) {
      if (t.isWater()) {
        continue;
      }
      if (TerritoryAttachment.get(t) != null && TerritoryAttachment.get(t).isCapital()) {
        // if they are a threat to take our capitol, dont move
        // compare the strength of units we can place
        final float ourStrength = AIUtils.strength(player.getUnits().getUnits(), false, false);
        final float attackerStrength = Utils.getStrengthOfPotentialAttackers(t, data);
        if (attackerStrength > ourStrength) {
          continue;
        }
      }
      // these are the units we can move
      final Match<Unit> moveOfType = Match.allOf(
          Matches.unitIsOwnedBy(player),
          Matches.UnitIsNotAA,
          // we can never move factories
          Matches.UnitCanMove,
          Matches.UnitIsNotInfrastructure,
          Matches.UnitIsLand);
      final Match<Territory> moveThrough =
          Match.allOf(Matches.TerritoryIsImpassable.invert(),
              Matches.TerritoryIsNeutralButNotWater.invert(), Matches.TerritoryIsLand);
      final List<Unit> units = t.getUnits().getMatches(moveOfType);
      if (units.size() == 0) {
        continue;
      }
      int minDistance = Integer.MAX_VALUE;
      Territory to = null;
      // find the nearest enemy owned capital
      for (final PlayerID otherPlayer : data.getPlayerList().getPlayers()) {
        final Territory capitol = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(otherPlayer, data);
        if (capitol != null && !data.getRelationshipTracker().isAllied(player, capitol.getOwner())) {
          final Route route = data.getMap().getRoute(t, capitol, moveThrough);
          if (route != null) {
            final int distance = route.numberOfSteps();
            if (distance != 0 && distance < minDistance) {
              minDistance = distance;
              to = capitol;
            }
          }
        }
      }
      if (to != null) {
        if (units.size() > 0) {
          moveUnits.add(units);
          final Route routeToCapitol = data.getMap().getRoute(t, to, moveThrough);
          final Territory firstStep = routeToCapitol.getAllTerritories().get(1);
          final Route route = new Route();
          route.setStart(t);
          route.add(firstStep);
          moveRoutes.add(route);
        }
      } else { // if we cant move to a capitol, move towards the enemy
        final Match<Territory> routeCondition =
            Match.allOf(Matches.TerritoryIsLand, Matches.TerritoryIsImpassable.invert());
        Route newRoute = Utils.findNearest(t, Matches.territoryHasEnemyLandUnits(player, data), routeCondition, data);
        // move to any enemy territory
        if (newRoute == null) {
          newRoute = Utils.findNearest(t, Matches.isTerritoryEnemy(player, data), routeCondition, data);
        }
        if (newRoute != null && newRoute.numberOfSteps() != 0) {
          moveUnits.add(units);
          final Territory firstStep = newRoute.getAllTerritories().get(1);
          final Route route = new Route();
          route.setStart(t);
          route.add(firstStep);
          moveRoutes.add(route);
        }
      }
    }
  }

  private void movePlanesHomeNonCom(final List<Collection<Unit>> moveUnits, final List<Route> moveRoutes,
      final PlayerID player, final GameData data) {
    // the preferred way to get the delegate
    final IMoveDelegate delegateRemote = (IMoveDelegate) getPlayerBridge().getRemoteDelegate();
    // this works because we are on the server
    final BattleDelegate delegate = DelegateFinder.battleDelegate(data);
    final Match<Territory> canLand = Match.allOf(
        Matches.isTerritoryAllied(player, data),
        Match.of(o -> !delegate.getBattleTracker().wasConquered(o)));
    final Match<Territory> routeCondition = Match.allOf(
        Matches.territoryHasEnemyAaForCombatOnly(player, data).invert(), Matches.TerritoryIsImpassable.invert());
    for (final Territory t : delegateRemote.getTerritoriesWhereAirCantLand()) {
      final Route noAaRoute = Utils.findNearest(t, canLand, routeCondition, data);
      final Route aaRoute = Utils.findNearest(t, canLand, Matches.TerritoryIsImpassable.invert(), data);
      final Collection<Unit> airToLand =
          t.getUnits().getMatches(Match.allOf(Matches.UnitIsAir, Matches.unitIsOwnedBy(player)));
      // dont bother to see if all the air units have enough movement points
      // to move without aa guns firing
      // simply move first over no aa, then with aa
      // one (but hopefully not both) will be rejected
      moveUnits.add(airToLand);
      moveRoutes.add(noAaRoute);
      moveUnits.add(airToLand);
      moveRoutes.add(aaRoute);
    }
  }

  private static void populateCombatMove(final GameData data, final List<Collection<Unit>> moveUnits,
      final List<Route> moveRoutes, final PlayerID player) {
    populateBomberCombat(data, moveUnits, moveRoutes, player);
    final Collection<Unit> unitsAlreadyMoved = new HashSet<>();
    // find the territories we can just walk into
    final Match<Territory> walkInto =
        Match.anyOf(Matches.isTerritoryEnemyAndNotUnownedWaterOrImpassableOrRestricted(player, data),
            Matches.isTerritoryFreeNeutral(data));
    final List<Territory> enemyOwned = Match.getMatches(data.getMap().getTerritories(), walkInto);
    Collections.shuffle(enemyOwned);
    Collections.sort(enemyOwned, (o1, o2) -> {
      // -1 means o1 goes first. 1 means o2 goes first. zero means they are equal.
      if (o1 == o2 || (o1 == null && o2 == null)) {
        return 0;
      }
      if (o1 == null) {
        return 1;
      }
      if (o2 == null) {
        return -1;
      }
      if (o1.equals(o2)) {
        return 0;
      }
      final TerritoryAttachment ta1 = TerritoryAttachment.get(o1);
      final TerritoryAttachment ta2 = TerritoryAttachment.get(o2);
      if (ta1 == null && ta2 == null) {
        return 0;
      }
      if (ta1 == null) {
        return 1;
      }
      if (ta2 == null) {
        return -1;
      }
      // take capitols first if we can
      if (ta1.isCapital() && !ta2.isCapital()) {
        return -1;
      }
      if (!ta1.isCapital() && ta2.isCapital()) {
        return 1;
      }
      final boolean factoryInT1 = o1.getUnits().someMatch(Matches.UnitCanProduceUnits);
      final boolean factoryInT2 = o2.getUnits().someMatch(Matches.UnitCanProduceUnits);
      // next take territories which can produce
      if (factoryInT1 && !factoryInT2) {
        return -1;
      }
      if (!factoryInT1 && factoryInT2) {
        return 1;
      }
      final boolean infrastructureInT1 = o1.getUnits().someMatch(Matches.UnitIsInfrastructure);
      final boolean infrastructureInT2 = o2.getUnits().someMatch(Matches.UnitIsInfrastructure);
      // next take territories with infrastructure
      if (infrastructureInT1 && !infrastructureInT2) {
        return -1;
      }
      if (!infrastructureInT1 && infrastructureInT2) {
        return 1;
      }
      // next take territories with largest PU value
      return ta2.getProduction() - ta1.getProduction();
    });
    final List<Territory> isWaterTerr = Utils.onlyWaterTerr(enemyOwned);
    enemyOwned.removeAll(isWaterTerr);
    // first find the territories we can just walk into
    for (final Territory enemy : enemyOwned) {
      if (AIUtils.strength(enemy.getUnits().getUnits(), false, false) == 0) {
        // only take it with 1 unit
        boolean taken = false;
        for (final Territory attackFrom : data.getMap().getNeighbors(enemy,
            Matches.territoryHasLandUnitsOwnedBy(player))) {
          if (taken) {
            break;
          }
          // get the cheapest unit to move in
          final List<Unit> unitsSortedByCost = new ArrayList<>(attackFrom.getUnits().getUnits());
          Collections.sort(unitsSortedByCost, AIUtils.getCostComparator());
          for (final Unit unit : unitsSortedByCost) {
            final Match<Unit> match = Match.allOf(Matches.unitIsOwnedBy(player), Matches.UnitIsLand,
                Matches.UnitIsNotInfrastructure, Matches.UnitCanMove, Matches.UnitIsNotAA,
                Matches.UnitCanNotMoveDuringCombatMove.invert());
            if (!unitsAlreadyMoved.contains(unit) && match.match(unit)) {
              moveRoutes.add(data.getMap().getRoute(attackFrom, enemy));
              // if unloading units, unload all of them,
              // otherwise we wont be able to unload them
              // in non com, for land moves we want to move the minimal
              // number of units, to leave units free to move elsewhere
              if (attackFrom.isWater()) {
                final List<Unit> units = attackFrom.getUnits().getMatches(Matches.unitIsLandAndOwnedBy(player));
                moveUnits.add(Util.difference(units, unitsAlreadyMoved));
                unitsAlreadyMoved.addAll(units);
              } else {
                moveUnits.add(Collections.singleton(unit));
              }
              unitsAlreadyMoved.add(unit);
              taken = true;
              break;
            }
          }
        }
      }
    }
    // find the territories we can reasonably expect to take
    for (final Territory enemy : enemyOwned) {
      final float enemyStrength = AIUtils.strength(enemy.getUnits().getUnits(), false, false);
      if (enemyStrength > 0) {
        final Match<Unit> attackable = Match.allOf(
            Matches.unitIsOwnedBy(player),
            Matches.UnitIsStrategicBomber.invert(),
            Match.of(o -> !unitsAlreadyMoved.contains(o)),
            Matches.UnitIsNotAA,
            Matches.UnitCanMove,
            Matches.UnitIsNotInfrastructure,
            Matches.UnitCanNotMoveDuringCombatMove.invert(),
            Matches.UnitIsNotSea);
        final Set<Territory> dontMoveFrom = new HashSet<>();
        // find our strength that we can attack with
        int ourStrength = 0;
        final Collection<Territory> attackFrom =
            data.getMap().getNeighbors(enemy, Matches.territoryHasLandUnitsOwnedBy(player));
        for (final Territory owned : attackFrom) {
          if (TerritoryAttachment.get(owned) != null && TerritoryAttachment.get(owned).isCapital()
              && (Utils.getStrengthOfPotentialAttackers(owned, data) > AIUtils.strength(owned.getUnits().getUnits(),
                  false, false))) {
            dontMoveFrom.add(owned);
            continue;
          }
          ourStrength += AIUtils.strength(owned.getUnits().getMatches(attackable), true, false);
        }
        // prevents 2 infantry from attacking 1 infantry
        if (ourStrength > 1.37 * enemyStrength) {
          // this is all we need to take it, dont go overboard, since we may be able to use the units to attack
          // somewhere else
          double remainingStrengthNeeded = (2.5 * enemyStrength) + 4;
          for (final Territory owned : attackFrom) {
            if (dontMoveFrom.contains(owned)) {
              continue;
            }
            List<Unit> units = owned.getUnits().getMatches(attackable);
            // only take the units we need if
            // 1) we are not an amphibious attack
            // 2) we can potentially attack another territory
            if (!owned.isWater()
                && data.getMap().getNeighbors(owned, Matches.territoryHasEnemyLandUnits(player, data)).size() > 1) {
              units = Utils.getUnitsUpToStrength(remainingStrengthNeeded, units, false);
            }
            remainingStrengthNeeded -= AIUtils.strength(units, true, false);
            if (units.size() > 0) {
              unitsAlreadyMoved.addAll(units);
              moveUnits.add(units);
              moveRoutes.add(data.getMap().getRoute(owned, enemy));
            }
          }
          s_logger.fine("Attacking : " + enemy + " our strength:" + ourStrength + " enemy strength" + enemyStrength
              + " remaining strength needed " + remainingStrengthNeeded);
        }
      }
    }
  }

  private static void populateBomberCombat(final GameData data, final List<Collection<Unit>> moveUnits,
      final List<Route> moveRoutes, final PlayerID player) {
    final Match<Territory> enemyFactory = Matches.territoryIsEnemyNonNeutralAndHasEnemyUnitMatching(data, player,
        Matches.UnitCanProduceUnitsAndCanBeDamaged);
    final Match<Unit> ownBomber = Match.allOf(Matches.UnitIsStrategicBomber, Matches.unitIsOwnedBy(player));
    for (final Territory t : data.getMap().getTerritories()) {
      final Collection<Unit> bombers = t.getUnits().getMatches(ownBomber);
      if (bombers.isEmpty()) {
        continue;
      }
      final Match<Territory> routeCond = Matches.territoryHasEnemyAaForCombatOnly(player, data).invert();
      final Route bombRoute = Utils.findNearest(t, enemyFactory, routeCond, data);
      moveUnits.add(bombers);
      moveRoutes.add(bombRoute);
    }
  }

  private static int countTransports(final GameData data, final PlayerID player) {
    final Match<Unit> ownedTransport = Match.allOf(Matches.UnitIsTransport, Matches.unitIsOwnedBy(player));
    int sum = 0;
    for (final Territory t : data.getMap()) {
      sum += t.getUnits().countMatches(ownedTransport);
    }
    return sum;
  }

  private static int countLandUnits(final GameData data, final PlayerID player) {
    final Match<Unit> ownedLandUnit = Match.allOf(Matches.UnitIsLand, Matches.unitIsOwnedBy(player));
    int sum = 0;
    for (final Territory t : data.getMap()) {
      sum += t.getUnits().countMatches(ownedLandUnit);
    }
    return sum;
  }

  @Override
  public void purchase(final boolean purchaseForBid, final int pusToSpend, final IPurchaseDelegate purchaseDelegate,
      final GameData data, final PlayerID player) {
    if (purchaseForBid) {
      // bid will only buy land units, due to weak ai placement for bid not being able to handle sea units
      final Resource PUs = data.getResourceList().getResource(Constants.PUS);
      int leftToSpend = pusToSpend;
      final List<ProductionRule> rules = player.getProductionFrontier().getRules();
      final IntegerMap<ProductionRule> purchase = new IntegerMap<>();
      int minCost = Integer.MAX_VALUE;
      int i = 0;
      while ((minCost == Integer.MAX_VALUE || leftToSpend >= minCost) && i < 100000) {
        i++;
        for (final ProductionRule rule : rules) {
          final NamedAttachable resourceOrUnit = rule.getResults().keySet().iterator().next();
          if (!(resourceOrUnit instanceof UnitType)) {
            continue;
          }
          final UnitType results = (UnitType) resourceOrUnit;
          if (Matches.UnitTypeIsSea.match(results) || Matches.UnitTypeIsAir.match(results)
              || Matches.UnitTypeIsInfrastructure.match(results) || Matches.UnitTypeIsAAforAnything.match(results)
              || Matches.UnitTypeHasMaxBuildRestrictions.match(results)
              || Matches.UnitTypeConsumesUnitsOnCreation.match(results)
              || Matches.unitTypeIsStatic(player).match(results)) {
            continue;
          }
          final int cost = rule.getCosts().getInt(PUs);
          if (cost < 1) {
            continue;
          }
          if (minCost == Integer.MAX_VALUE) {
            minCost = cost;
          }
          if (minCost > cost) {
            minCost = cost;
          }
          // give a preference to cheap units
          if (Math.random() * cost < 2) {
            if (cost <= leftToSpend) {
              leftToSpend -= cost;
              purchase.add(rule, 1);
            }
          }
        }
      }
      purchaseDelegate.purchase(purchase);
      pause();
      return;
    }
    final boolean isAmphib = isAmphibAttack(player, data);
    final Route amphibRoute = getAmphibRoute(player, data);
    final int transportCount = countTransports(data, player);
    final int landUnitCount = countLandUnits(data, player);
    int defUnitsAtAmpibRoute = 0;
    if (isAmphib && amphibRoute != null) {
      defUnitsAtAmpibRoute = amphibRoute.getEnd().getUnits().getUnitCount();
    }
    final Resource PUs = data.getResourceList().getResource(Constants.PUS);
    final int totalPu = player.getResources().getQuantity(PUs);
    int leftToSpend = totalPu;
    final Territory capitol = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
    final List<ProductionRule> rules = player.getProductionFrontier().getRules();
    final IntegerMap<ProductionRule> purchase = new IntegerMap<>();
    List<RepairRule> rrules = Collections.emptyList();
    final Match<Unit> ourFactories = Match.allOf(Matches.unitIsOwnedBy(player), Matches.UnitCanProduceUnits);
    final List<Territory> rfactories =
        Match.getMatches(Utils.findUnitTerr(data, ourFactories), Matches.isTerritoryOwnedBy(player));
    // figure out if anything needs to be repaired
    if (player.getRepairFrontier() != null
        && games.strategy.triplea.Properties.getDamageFromBombingDoneToUnitsInsteadOfTerritories(data)) {
      rrules = player.getRepairFrontier().getRules();
      final IntegerMap<RepairRule> repairMap = new IntegerMap<>();
      final HashMap<Unit, IntegerMap<RepairRule>> repair = new HashMap<>();
      final Map<Unit, Territory> unitsThatCanProduceNeedingRepair = new HashMap<>();
      final int minimumUnitPrice = 3;
      int diff = 0;
      int capProduction = 0;
      Unit capUnit = null;
      Territory capUnitTerritory = null;
      int currentProduction = 0;
      // we should sort this
      Collections.shuffle(rfactories);
      for (final Territory fixTerr : rfactories) {
        if (!Matches.territoryIsOwnedAndHasOwnedUnitMatching(player, Matches.UnitCanProduceUnitsAndCanBeDamaged)
            .match(fixTerr)) {
          continue;
        }
        final Unit possibleFactoryNeedingRepair = TripleAUnit.getBiggestProducer(
            Match.getMatches(fixTerr.getUnits().getUnits(), ourFactories), fixTerr, player, data, false);
        if (Matches.UnitHasTakenSomeBombingUnitDamage.match(possibleFactoryNeedingRepair)) {
          unitsThatCanProduceNeedingRepair.put(possibleFactoryNeedingRepair, fixTerr);
        }
        final TripleAUnit taUnit = (TripleAUnit) possibleFactoryNeedingRepair;
        diff = taUnit.getUnitDamage();
        if (fixTerr == capitol) {
          capProduction =
              TripleAUnit.getHowMuchCanUnitProduce(possibleFactoryNeedingRepair, fixTerr, player, data, true, true);
          capUnit = possibleFactoryNeedingRepair;
          capUnitTerritory = fixTerr;
        }
        currentProduction +=
            TripleAUnit.getHowMuchCanUnitProduce(possibleFactoryNeedingRepair, fixTerr, player, data, true, true);
      }
      rfactories.remove(capitol);
      unitsThatCanProduceNeedingRepair.remove(capUnit);
      // assume minimum unit price is 3, and that we are buying only that... if we over repair, oh well, that is better
      // than under-repairing
      // goal is to be able to produce all our units, and at least half of that production in the capitol
      //
      // if capitol is super safe, we don't have to do this. and if capitol is under siege, we should repair enough to
      // place all our units here
      int maxUnits = (totalPu - 1) / minimumUnitPrice;
      if ((capProduction <= maxUnits / 2 || rfactories.isEmpty()) && capUnit != null) {
        for (final RepairRule rrule : rrules) {
          if (!capUnit.getUnitType().equals(rrule.getResults().keySet().iterator().next())) {
            continue;
          }
          if (!Matches.territoryIsOwnedAndHasOwnedUnitMatching(player, Matches.UnitCanProduceUnitsAndCanBeDamaged)
              .match(capitol)) {
            continue;
          }
          final TripleAUnit taUnit = (TripleAUnit) capUnit;
          diff = taUnit.getUnitDamage();
          final int unitProductionAllowNegative =
              TripleAUnit.getHowMuchCanUnitProduce(capUnit, capUnitTerritory, player, data, false, true) - diff;
          if (!rfactories.isEmpty()) {
            diff = Math.min(diff, (maxUnits / 2 - unitProductionAllowNegative) + 1);
          } else {
            diff = Math.min(diff, (maxUnits - unitProductionAllowNegative));
          }
          diff = Math.min(diff, leftToSpend - minimumUnitPrice);
          if (diff > 0) {
            if (unitProductionAllowNegative >= 0) {
              currentProduction += diff;
            } else {
              currentProduction += diff + unitProductionAllowNegative;
            }
            repairMap.add(rrule, diff);
            repair.put(capUnit, repairMap);
            leftToSpend -= diff;
            purchaseDelegate.purchaseRepair(repair);
            repair.clear();
            repairMap.clear();
            // ideally we would adjust this after each single PU spent, then re-evaluate
            // everything.
            maxUnits = (leftToSpend - 1) / minimumUnitPrice;
          }
        }
      }
      int i = 0;
      while (currentProduction < maxUnits && i < 2) {
        for (final RepairRule rrule : rrules) {
          for (final Unit fixUnit : unitsThatCanProduceNeedingRepair.keySet()) {
            if (fixUnit == null || !fixUnit.getType().equals(rrule.getResults().keySet().iterator().next())) {
              continue;
            }
            if (!Matches.territoryIsOwnedAndHasOwnedUnitMatching(player, Matches.UnitCanProduceUnitsAndCanBeDamaged)
                .match(unitsThatCanProduceNeedingRepair.get(fixUnit))) {
              continue;
            }
            // we will repair the first territories in the list as much as we can, until we fulfill the condition, then
            // skip all other
            // territories
            if (currentProduction >= maxUnits) {
              continue;
            }
            final TripleAUnit taUnit = (TripleAUnit) fixUnit;
            diff = taUnit.getUnitDamage();
            final int unitProductionAllowNegative = TripleAUnit.getHowMuchCanUnitProduce(fixUnit,
                unitsThatCanProduceNeedingRepair.get(fixUnit), player, data, false, true) - diff;
            if (i == 0) {
              if (unitProductionAllowNegative < 0) {
                diff = Math.min(diff, (maxUnits - currentProduction) - unitProductionAllowNegative);
              } else {
                diff = Math.min(diff, (maxUnits - currentProduction));
              }
            }
            diff = Math.min(diff, leftToSpend - minimumUnitPrice);
            if (diff > 0) {
              if (unitProductionAllowNegative >= 0) {
                currentProduction += diff;
              } else {
                currentProduction += diff + unitProductionAllowNegative;
              }
              repairMap.add(rrule, diff);
              repair.put(fixUnit, repairMap);
              leftToSpend -= diff;
              purchaseDelegate.purchaseRepair(repair);
              repair.clear();
              repairMap.clear();
              // ideally we would adjust this after each single PU spent, then re-evaluate
              // everything.
              maxUnits = (leftToSpend - 1) / minimumUnitPrice;
            }
          }
        }
        rfactories.add(capitol);
        if (capUnit != null) {
          unitsThatCanProduceNeedingRepair.put(capUnit, capUnitTerritory);
        }
        i++;
      }
    }
    int minCost = Integer.MAX_VALUE;
    int i = 0;
    while ((minCost == Integer.MAX_VALUE || leftToSpend >= minCost) && i < 100000) {
      i++;
      for (final ProductionRule rule : rules) {
        final NamedAttachable resourceOrUnit = rule.getResults().keySet().iterator().next();
        if (!(resourceOrUnit instanceof UnitType)) {
          continue;
        }
        final UnitType results = (UnitType) resourceOrUnit;
        if (Matches.UnitTypeIsAir.match(results) || Matches.UnitTypeIsInfrastructure.match(results)
            || Matches.UnitTypeIsAAforAnything.match(results) || Matches.UnitTypeHasMaxBuildRestrictions.match(results)
            || Matches.UnitTypeConsumesUnitsOnCreation.match(results)
            || Matches.unitTypeIsStatic(player).match(results)) {
          continue;
        }
        final int transportCapacity = UnitAttachment.get(results).getTransportCapacity();
        // buy transports if we can be amphibious
        if (Matches.UnitTypeIsSea.match(results)) {
          if (!isAmphib || transportCapacity <= 0) {
            continue;
          }
        }
        final int cost = rule.getCosts().getInt(PUs);
        if (cost < 1) {
          continue;
        }
        if (minCost == Integer.MAX_VALUE) {
          minCost = cost;
        }
        if (minCost > cost) {
          minCost = cost;
        }
        // give a preferene to cheap units, and to transports
        // but dont go overboard with buying transports
        int goodNumberOfTransports = 0;
        final boolean isTransport = transportCapacity > 0;
        if (amphibRoute != null) {
          // 25% transports - can be more if frontier is far away
          goodNumberOfTransports = (landUnitCount / 4);
          // boost for transport production
          if (isTransport && defUnitsAtAmpibRoute > goodNumberOfTransports && landUnitCount > defUnitsAtAmpibRoute
              && defUnitsAtAmpibRoute > transportCount) {
            final int transports = (leftToSpend / cost);
            leftToSpend -= cost * transports;
            purchase.add(rule, transports);
            continue;
          }
        }
        final boolean buyBecauseTransport =
            (Math.random() < 0.7 && transportCount < goodNumberOfTransports) || Math.random() < 0.10;
        final boolean dontBuyBecauseTooManyTransports = transportCount > 2 * goodNumberOfTransports;
        if ((!isTransport && Math.random() * cost < 2)
            || (isTransport && buyBecauseTransport && !dontBuyBecauseTooManyTransports)) {
          if (cost <= leftToSpend) {
            leftToSpend -= cost;
            purchase.add(rule, 1);
          }
        }
      }
    }
    purchaseDelegate.purchase(purchase);
    pause();
  }

  @Override
  public void place(final boolean bid, final IAbstractPlaceDelegate placeDelegate, final GameData data,
      final PlayerID player) {
    if (player.getUnits().size() == 0) {
      return;
    }
    final Territory capitol = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
    // place in capitol first
    placeAllWeCanOn(data, capitol, placeDelegate, player);
    final List<Territory> randomTerritories = new ArrayList<>(data.getMap().getTerritories());
    Collections.shuffle(randomTerritories);
    for (final Territory t : randomTerritories) {
      if (t != capitol && t.getOwner().equals(player) && t.getUnits().someMatch(Matches.UnitCanProduceUnits)) {
        placeAllWeCanOn(data, t, placeDelegate, player);
      }
    }
  }

  private void placeAllWeCanOn(final GameData data, final Territory placeAt, final IAbstractPlaceDelegate placeDelegate,
      final PlayerID player) {
    final PlaceableUnits pu = placeDelegate.getPlaceableUnits(player.getUnits().getUnits(), placeAt);
    if (pu.getErrorMessage() != null) {
      return;
    }
    int placementLeft = pu.getMaxUnits();
    if (placementLeft == -1) {
      placementLeft = Integer.MAX_VALUE;
    }
    final List<Unit> seaUnits = new ArrayList<>(player.getUnits().getMatches(Matches.UnitIsSea));
    if (seaUnits.size() > 0) {
      final Route amphibRoute = getAmphibRoute(player, data);
      Territory seaPlaceAt = null;
      if (amphibRoute != null) {
        seaPlaceAt = amphibRoute.getAllTerritories().get(1);
      } else {
        final Set<Territory> seaNeighbors = data.getMap().getNeighbors(placeAt, Matches.TerritoryIsWater);
        if (!seaNeighbors.isEmpty()) {
          seaPlaceAt = seaNeighbors.iterator().next();
        }
      }
      if (seaPlaceAt != null) {
        final int seaPlacement = Math.min(placementLeft, seaUnits.size());
        placementLeft -= seaPlacement;
        final Collection<Unit> toPlace = seaUnits.subList(0, seaPlacement);
        doPlace(seaPlaceAt, toPlace, placeDelegate);
      }
    }
    final List<Unit> landUnits = new ArrayList<>(player.getUnits().getMatches(Matches.UnitIsLand));
    if (!landUnits.isEmpty()) {
      final int landPlaceCount = Math.min(placementLeft, landUnits.size());
      placementLeft -= landPlaceCount;
      final Collection<Unit> toPlace = landUnits.subList(0, landPlaceCount);
      doPlace(placeAt, toPlace, placeDelegate);
    }
  }

  private void doPlace(final Territory where, final Collection<Unit> toPlace, final IAbstractPlaceDelegate del) {
    final String message = del.placeUnits(new ArrayList<>(toPlace), where, IAbstractPlaceDelegate.BidMode.NOT_BID);
    if (message != null) {
      s_logger.fine(message);
      s_logger.fine("Attempt was at:" + where + " with:" + toPlace);
    }
    pause();
  }

  @Override
  public boolean shouldBomberBomb(final Territory territory) {
    return true;
  }

  public static final Match<Unit> Transporting = Match.of(o -> TripleAUnit.get(o).getTransporting().size() > 0);
}
