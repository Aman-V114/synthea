package org.mitre.synthea.world.agents;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.SimpleCSV;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.behaviors.payeradjustment.IPayerAdjustment;
import org.mitre.synthea.world.agents.behaviors.payeradjustment.PayerAdjustmentFixed;
import org.mitre.synthea.world.agents.behaviors.payeradjustment.PayerAdjustmentNone;
import org.mitre.synthea.world.agents.behaviors.payeradjustment.PayerAdjustmentRandom;
import org.mitre.synthea.world.agents.behaviors.planeligibility.PlanEligibilityFinder;
import org.mitre.synthea.world.agents.behaviors.planfinder.IPlanFinder;
import org.mitre.synthea.world.agents.behaviors.planfinder.PlanFinderBestRates;
import org.mitre.synthea.world.agents.behaviors.planfinder.PlanFinderGovPriority;
import org.mitre.synthea.world.agents.behaviors.planfinder.PlanFinderRandom;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.healthinsurance.InsurancePlan;
import org.mitre.synthea.world.geography.Location;

/**
 * A class that maintains and manages Payers.
 */
public class PayerManager {

  // Payer adjustment algorithm choices:
  private static final String NONE = "none";
  private static final String FIXED = "fixed";

  public static final String GOV_OWNERSHIP = "GOVERNMENT";
  public static final String PRIVATE_OWNERSHIP = "PRIVATE";
  public static final String NO_INSURANCE = "NO_INSURANCE";

  public static final String MEDICARE =
      Config.get("generate.payers.insurance_companies.medicare", "Medicare");
  public static final String MEDICAID =
      Config.get("generate.payers.insurance_companies.medicaid", "Medicaid");
  public static final String DUAL_ELIGIBLE =
      Config.get("generate.payers.insurance_companies.dual_eligible", "Dual Eligible");

  /* ArrayList of all Private Payers imported. */
  private static List<Payer> privatePayers = new ArrayList<Payer>();
  /* Map of all Government Payers imported. */
  private static Map<String, Payer> governmentPayers = new HashMap<String, Payer>();
  /* No Insurance Payer. */
  public static Payer noInsurance;

  /* U.S. States loaded. */
  private static Set<String> statesLoaded = new HashSet<String>();

  // Plan Finder.
  private static IPlanFinder planFinder;
  // Payer selection algorithm choices:
  private static final String RANDOM = "random";
  private static final String BESTRATE = "best_rate";
  private static final String GOVPRIORITY = "gov_priority";

  /**
   * Load into cache the list of payers for a state.
   *
   * @param location the state being loaded.
   */
  public static void loadPayers(Location location) {
    // Load the plan eligibility algorithms.
    String eligibilitiesFile = Config.get("generate.payers.insurance_plans.eligibilities_file");
    PlanEligibilityFinder.buildPlanEligibilities(location.state, eligibilitiesFile);

    // Build the Plan Finder.
    planFinder = buildPlanFinder();
    if (!statesLoaded.contains(location.state)
        || !statesLoaded.contains(Location.getAbbreviation(location.state))
        || !statesLoaded.contains(Location.getStateName(location.state))) {
      try {
        String payerFile = Config.get("generate.payers.insurance_companies.default_file");
        loadPayers(location, payerFile);

        statesLoaded.add(location.state);
        statesLoaded.add(Location.getAbbreviation(location.state));
        statesLoaded.add(Location.getStateName(location.state));
      } catch (IOException e) {
        System.err.println("ERROR: unable to load payers for state: " + location.state);
        e.printStackTrace();
      }
    }
  }

  /**
   * Read the payers from the given resource file, only importing the ones for the
   * given state.
   *
   * @param location the state being loaded
   * @param fileName Location of the file, relative to src/main/resources
   * @throws IOException if the file cannot be read
   */
  private static void loadPayers(Location location, String fileName) throws IOException {
    PayerManager.loadNoInsurance();

    String resource = Utilities.readResource(fileName);
    Iterator<? extends Map<String, String>> csv = SimpleCSV.parseLineByLine(resource);

    while (csv.hasNext()) {
      Map<String, String> row = csv.next();
      String payerStates = row.get("states_covered").toUpperCase();
      String abbreviation = Location.getAbbreviation(location.state).toUpperCase();

      // For now, only allow one U.S. state at a time.
      if (payerStates.contains(abbreviation) || payerStates.contains("*")) {

        Payer parsedPayer = csvLineToPayer(row);
        parsedPayer.setPayerAdjustment(buildPayerAdjustment());

        // Put the payer in their correct List/Map based on Government/Private.
        if (parsedPayer.isGovernmentPayer()) {
          // Government payers go in a map, allowing for easy retrieval of specific
          // government payers.
          PayerManager.governmentPayers.put(parsedPayer.getName(), parsedPayer);
        } else {
          // Private payers go in a list.
          PayerManager.privatePayers.add(parsedPayer);
        }
      }
    }

    PayerManager.loadPlans();
  }

  private static void loadPlans() {
    String fileName = Config.get("generate.payers.insurance_plans.default_file");
    Iterator<? extends Map<String, String>> csv = null;
    try {
      String resource = Utilities.readResource(fileName);
      csv = SimpleCSV.parseLineByLine(resource);
    } catch (IOException e) {
      e.printStackTrace();
    }

    while (csv.hasNext()) {
      Map<String, String> row = csv.next();
      csvLineToPlan(row);
    }
  }

  /**
   * Determines the algorithm to use for patients to find a Payer.
   */
  private static IPlanFinder buildPlanFinder() {
    IPlanFinder finder;
    String behavior = Config.get("generate.payers.selection_behavior").toLowerCase();
    switch (behavior) {
      case BESTRATE:
        finder = new PlanFinderBestRates();
        break;
      case RANDOM:
        finder = new PlanFinderRandom();
        break;
      case GOVPRIORITY:
        finder = new PlanFinderGovPriority();
        break;
      default:
        throw new RuntimeException("Not a valid Payer Selection Algorithm: " + behavior);
    }
    return finder;
  }

  private static IPayerAdjustment buildPayerAdjustment() {
    IPayerAdjustment adjustment;
    String behavior = Config.get("generate.payers.adjustment_behavior", "none").toLowerCase();
    String rateString = Config.get("generate.payers.adjustment_rate", "0.05");
    double rate = Double.parseDouble(rateString);
    switch (behavior) {
      case NONE:
        adjustment = new PayerAdjustmentNone();
        break;
      case FIXED:
        adjustment = new PayerAdjustmentFixed(rate);
        break;
      case RANDOM:
        adjustment = new PayerAdjustmentRandom(rate);
        break;
      default:
        adjustment = new PayerAdjustmentNone();
    }
    return adjustment;
  }

  /**
   * Given a line of parsed CSV input, convert the data into a Payer.
   *
   * @param line read a csv line to a payer's attributes
   * @return the new payer.
   */
  private static Payer csvLineToPayer(Map<String, String> line) {

    // Uses .remove() instead of .get() so we can iterate over remaining keys later.
    String payerName = line.remove("name");
    String payerId = line.remove("id");
    Set<String> statesCovered = commaSeparatedStringToHashSet(line.remove("states_covered"));
    String ownership = line.remove("ownership");
    if (ownership.equalsIgnoreCase(GOV_OWNERSHIP)
        || ownership.equalsIgnoreCase(PRIVATE_OWNERSHIP)) {
      ownership = ownership.toUpperCase();
    } else {
      throw new RuntimeException("A Payer's ownership must be tagged as either "
          + GOV_OWNERSHIP + " or " + PRIVATE_OWNERSHIP + ". Payer " + payerName
          + " " + payerId + " has ownership of " + ownership + ".");
    }

    Payer newPayer = new Payer(payerName, payerId, statesCovered, ownership);

    // Add remaining columns we didn't map to first-class fields to payer's
    // attributes map.
    for (Map.Entry<String, String> e : line.entrySet()) {
      newPayer.addAttribute(e.getKey(), e.getValue());
    }

    return newPayer;
  }

  private static void csvLineToPlan(Map<String, String> line) {
    // Uses .remove() instead of .get() so we can iterate over remaining keys later.
    String payerId = line.remove("payer_id");
    String planId = line.remove("plan_id");
    String planName = line.remove("name");
    Set<String> servicesCovered = commaSeparatedStringToHashSet(line.remove("services_covered"));
    double deductible = Double.parseDouble(line.remove("deductible"));
    double defaultCoinsurance = Double.parseDouble(line.remove("default_coinsurance"));
    double defaultCopay = Double.parseDouble(line.remove("default_copay"));
    double monthlyPremium = Double.parseDouble(line.remove("monthly_premium"));
    boolean medicareSupplement = Boolean.parseBoolean(line.remove("medicare_supplement"));
    String eligibilityName = line.remove("eligibility_algorithm");
    int startAvailable = Integer.parseInt(line.remove("start_availability"));
    String endAvailableStr = line.remove("end_availablity");
    int endAvailable = 0;
    if (!endAvailableStr.equals("")) {
      endAvailable = Integer.parseInt(endAvailableStr);
    }
    Payer payer = PayerManager.getPayerById(payerId);
    payer.createPlan(servicesCovered, deductible, defaultCoinsurance,
        defaultCopay, monthlyPremium, medicareSupplement, eligibilityName, startAvailable, endAvailable);
  }

  private static Payer getPayerById(String payerId) {
    List<Payer> payerList = getAllPayers().stream().filter(payer ->
        payer.getPlanLinkId().equals(payerId)).collect(Collectors.toList());
    if (!payerList.isEmpty()) {
      return payerList.get(0);
    }
    return null;
  }

  /**
   * Given a comma seperated string, convert the data into a Set.
   *
   * @param field the string to extract the Set from.
   * @return the HashSet of services covered.
   */
  private static Set<String> commaSeparatedStringToHashSet(String field) {
    String[] commaSeparatedField = field.split("\\s*,\\s*");
    List<String> parsedValues = Arrays.stream(commaSeparatedField).collect(Collectors.toList());
    return new HashSet<String>(parsedValues);
  }

  /**
   * Loads the noInsurance Payer.
   */
  public static void loadNoInsurance() {
    // noInsurance 'covers' all states.
    Set<String> statesCovered = new HashSet<String>();
    statesCovered.add("*");
    PayerManager.noInsurance = new Payer(NO_INSURANCE, "000000",
        statesCovered, NO_INSURANCE);
    PayerManager.noInsurance.createPlan(new HashSet<String>(), 0.0, 0.0, 0.0, 0.0, false, "generic", 0, 0);
    PayerManager.noInsurance.setPayerAdjustment(new PayerAdjustmentNone());
  }

  /**
   * Returns the list of all loaded private payers.
   */
  public static List<Payer> getPrivatePayers() {
    return PayerManager.privatePayers;
  }

  /**
   * Returns the List of all loaded government payers.
   */
  public static List<Payer> getGovernmentPayers() {
    return PayerManager.governmentPayers.values().stream().collect(Collectors.toList());
  }

  /**
   * Returns a List of all loaded payers.
   */
  public static List<Payer> getAllPayers() {
    List<Payer> allPayers = new ArrayList<>();
    allPayers.addAll(PayerManager.getGovernmentPayers());
    allPayers.addAll(PayerManager.getPrivatePayers());
    return allPayers;
  }

  /**
   * Returns the government payer with the given name.
   *
   * @param governmentPayerName the government payer to get.
   * @return returns null if the government payer does not exist.
   */
  public static Payer getGovernmentPayer(String governmentPayerName) {
    return PayerManager.governmentPayers.get(governmentPayerName);
  }

  /**
   * Clear the list of loaded and cached Payers.
   * Currently only used in tests.
   */
  public static void clear() {
    governmentPayers.clear();
    privatePayers.clear();
    statesLoaded.clear();
    planFinder = buildPlanFinder();
  }

  /**
   * Returns a Payer that the person can qualify for.
   *
   * @param person  the person who needs to find insurance.
   * @param service the EncounterType the person would like covered.
   * @param time    the time that the person requires insurance.
   * @return a payer who the person can accept and vice versa.
   */
  public static InsurancePlan findPlan(Person person, EncounterType service, long time) {
    Set<InsurancePlan> plans = getActivePlans(getAllPayers(), time);
    // Remove medicare supplement plans from this check.
    plans = plans.stream().filter(plan -> !plan.isMedicareSupplementPlan())
        .collect(Collectors.toSet());
    InsurancePlan potentialPlan = planFinder.find(plans, person, service, time);
    if (potentialPlan.isGovernmentPlan()) {
      // Person will always choose a government plan.
      return potentialPlan;
    }
    // If the person cannot get a government plan, they will try to keep their existing insurance.
    InsurancePlan previousPlan = person.coverage
        .getPlanAtTime(time - Config.getAsLong("generate.timestep"));
    if (previousPlan != null && !previousPlan.isNoInsurance()
        && IPlanFinder.meetsAffordabilityRequirements(previousPlan, person, null, time)) {
      // People will keep their previous year's insurance if they can.
      return previousPlan;
    }
    return potentialPlan;
  }

  /**
   * Returns all plans loaded by the set of payers.
   * @return The set of plans.
   */
  /**
   * Returns all active plans in the given payers based on the given time.
   * @param payers  The payers.
   * @param time  The time for the plan to be active in.
   * @return The set of active plans.
   */
  public static Set<InsurancePlan> getActivePlans(List<Payer> payers, long time) {
    Set<InsurancePlan> plans = payers.stream().map(payer ->
        payer.getPlans()).flatMap(Set::stream).collect(Collectors.toSet());
    plans = plans.stream().filter(plan -> plan.isActive(time)).collect(Collectors.toSet());
    return plans;
  }

  /**
   * Returns the no insurance plan.
   * @return
   */
  public static InsurancePlan getNoInsurancePlan() {
    return noInsurance.getPlans().iterator().next();
  }

  /**
   * Finds an eligible medicare supplement plan for the given person.
   * @param person  The person for whom to find a medicare supplement plan.
   * @param service The service the plan should cover.
   * @param time  The time.
   * @return  A potential Medicare Supplement plan, if eligible and affordable.
   */
  public static InsurancePlan findMedicareSupplement(Person person,
      EncounterType service, long time) {
    Set<InsurancePlan> plans = getActivePlans(getAllPayers(), time);
    // Remove non-medicare supplement plans from this check.
    plans = plans.stream().filter(plan ->
        plan.isMedicareSupplementPlan()).collect(Collectors.toSet());
    InsurancePlan potentialPlan = planFinder
        .find(plans, person, service, time);
    return potentialPlan;
  }
}