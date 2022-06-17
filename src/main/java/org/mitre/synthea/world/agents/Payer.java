package org.mitre.synthea.world.agents;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.gson.internal.LinkedTreeMap;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.mitre.synthea.export.JSONSkip;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.behaviors.payeradjustment.IPayerAdjustment;
import org.mitre.synthea.world.concepts.Claim;
import org.mitre.synthea.world.concepts.Claim.ClaimEntry;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;
import org.mitre.synthea.world.concepts.HealthRecord.Immunization;
import org.mitre.synthea.world.concepts.HealthRecord.Medication;
import org.mitre.synthea.world.concepts.HealthRecord.Procedure;
import org.mitre.synthea.world.concepts.healthinsurance.InsurancePlan;

public class Payer implements Serializable {

  // Payer Adjustment strategy.
  @JSONSkip
  private IPayerAdjustment payerAdjustment;

  /* Payer Attributes. */
  private final Map<String, Object> attributes;
  private final String name;
  public final String uuid;
  private final Set<InsurancePlan> plans;
  private final String ownership;
  private final int priority;

  // The States that this payer covers & operates in.
  private final Set<String> statesCovered;

  /* Payer Statistics. */
  private BigDecimal revenue;
  private BigDecimal costsCovered;
  private BigDecimal costsUncovered;
  private double totalQOLS; // Total customer quality of life scores.
  // Unique utilizers of Payer, by Person ID, with number of utilizations per Person.
  private final Map<String, AtomicInteger> customerUtilization;
  // row: year, column: type, value: count.
  private transient Table<Integer, String, AtomicInteger> entryUtilization;
  private final String planLinkId;

  /**
   * Simple bean used to add Java Serialization support to
   * com.google.common.collect.Table&lt;Integer, String, AtomicInteger&gt; which doesn't natively
   * support Serialization.
   */
  static class UtilizationBean implements Serializable {
    public Integer year;
    public String type;
    public AtomicInteger count;

    public UtilizationBean(Integer year, String type, AtomicInteger count) {
      this.year = year;
      this.type = type;
      this.count = count;
    }
  }

  /**
   * Java Serialization support for the entryUtilization field.
   * @param oos stream to write to
   */
  private void writeObject(ObjectOutputStream oos) throws IOException {
    oos.defaultWriteObject();
    ArrayList<UtilizationBean> entryUtilizationElements = null;
    if (entryUtilization != null) {
      entryUtilizationElements = new ArrayList<>(entryUtilization.size());
      for (Table.Cell<Integer, String, AtomicInteger> cell: entryUtilization.cellSet()) {
        entryUtilizationElements.add(
                new UtilizationBean(cell.getRowKey(), cell.getColumnKey(), cell.getValue()));
      }
    }
    oos.writeObject(entryUtilizationElements);
  }

  /**
   * Java Serialization support for the entryUtilization field.
   * @param ois stream to read from
   */
  private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
    ois.defaultReadObject();
    ArrayList<UtilizationBean> entryUtilizationElements =
            (ArrayList<UtilizationBean>)ois.readObject();
    if (entryUtilizationElements != null) {
      this.entryUtilization = HashBasedTable.create();
      for (UtilizationBean u: entryUtilizationElements) {
        this.entryUtilization.put(u.year, u.type, u.count);
      }
    }
  }

  /**
   * Payer constructor.
   * @param name  The name of the payer.
   * @param id  The payer ID.
   * @param statesCovered The list of states covered.
   * @param ownership The type of ownership (private/government).
   * @param priority
   */
  public Payer(String name, String id, Set<String> statesCovered, String ownership, int priority) {
    if (name == null || name.isEmpty()) {
      throw new RuntimeException("ERROR: Payer must have a non-null name. Payer ID: " + id + ".");
    }
    // Attributes.
    this.name = name;
    this.planLinkId = id;
    this.uuid = UUID.nameUUIDFromBytes((id + this.name).getBytes()).toString();
    this.statesCovered = statesCovered;
    this.plans = new HashSet<InsurancePlan>();
    this.ownership = ownership;
    if (priority == 0) {
      priority = Integer.MAX_VALUE;
    }
    this.priority = priority;
    this.attributes = new LinkedTreeMap<>();

    // Tracking values.
    this.entryUtilization = HashBasedTable.create();
    this.customerUtilization = new HashMap<String, AtomicInteger>();
    this.costsCovered = Claim.ZERO_CENTS;
    this.costsUncovered = Claim.ZERO_CENTS;
    this.revenue = Claim.ZERO_CENTS;
    this.totalQOLS = 0.0;
  }

  /**
   * Creates and adds a new plan with the given attributes to this payer.
   * @param servicesCovered The services covered.
   * @param deductible  The deductible.
   * @param defaultCoinsurance  The default coinsurance.
   * @param defaultCopay  The default copay.
   * @param monthlyPremium  The monthly premium.
   */
  public void createPlan(Set<String> servicesCovered, double deductible, double defaultCoinsurance,
      double defaultCopay, double monthlyPremium, boolean medicareSupplement, String eligibilityName, int yearStart, int yearEnd) {
    InsurancePlan newPlan = new InsurancePlan(
        this, servicesCovered, BigDecimal.valueOf(deductible),
        BigDecimal.valueOf(defaultCoinsurance), BigDecimal.valueOf(defaultCopay),
        BigDecimal.valueOf(monthlyPremium), medicareSupplement, eligibilityName, yearStart, yearEnd);
    this.plans.add(newPlan);
  }

  /**
   * Returns the payer's unique ID.
   */
  public String getResourceID() {
    return uuid;
  }

  /**
   * Returns the name of the payer.
   */
  public String getName() {
    return this.name;
  }

  /**
   * Returns the ownership type of the payer (Government/Private).
   */
  public String getOwnership() {
    return this.ownership;
  }

  /**
   * Returns the priority level of this payer.
   */
  public int getPriority() {
    return this.priority;
  }

  /**
   * Returns the Map of the payer's second class attributes.
   */
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  /**
   * Returns the set of plans offered by this payer.
   */
  public Set<InsurancePlan> getPlans() {
    return this.plans;
  }

  /**
   * Is the given Provider in this Payer's network?.
   * Currently just returns true until Networks are implemented.
   *
   * @param provider Provider to consider
   * @return whether or not the provider is in the payer network
   */
  public boolean isInNetwork(Provider provider) {
    return true;
  }

  /**
   * Returns whether or not this payer will cover the given entry.
   *
   * @param entry the entry that needs covering.
   */
  public boolean coversCare(Entry entry) {
    // Payer.isInNetwork() always returns true. For Now.
    return this.plans.iterator().next().coversService(entry)
        && this.isInNetwork(null);
    // Entry doesn't have a provider but encounter does, need to find a way to get provider.
  }

  /**
   * Determines whether or not this payer will adjust this claim, and by how
   * much. This determination is based on the claim adjustment strategy configuration,
   * which defaults to none.
   * @param claimEntry The claim entry to be adjusted.
   * @param person The person making the claim.
   * @return The dollar amount the claim entry was adjusted.
   */
  public BigDecimal adjustClaim(ClaimEntry claimEntry, Person person) {
    return payerAdjustment.adjustClaim(claimEntry, person);
  }

  /**
   * Increments the number of unique users.
   *
   * @param person the person to add to the payer.
   */
  public synchronized void incrementCustomers(Person person) {
    String personId = (String) person.attributes.get(Person.ID);
    if (!customerUtilization.containsKey(personId)) {
      customerUtilization.put(personId, new AtomicInteger(0));
    }
    customerUtilization.get(personId).incrementAndGet();
  }

  /**
   * Increments the entries covered by this payer.
   *
   * @param entry the entry covered.
   */
  public void incrementCoveredEntries(Entry entry) {

    String entryType = getEntryType(entry);

    incrementEntries(Utilities.getYear(entry.start), "covered-" + entryType);
    incrementEntries(Utilities.getYear(entry.start), "covered-" + entryType + "-" + entry.type);
  }

  /**
   * Increments the entries not covered by this payer.
   *
   * @param entry the entry covered.
   */
  public void incrementUncoveredEntries(Entry entry) {

    String entryType = getEntryType(entry);

    incrementEntries(Utilities.getYear(entry.start), "uncovered-" + entryType);
    incrementEntries(Utilities.getYear(entry.start), "uncovered-" + entryType
        + "-" + entry.type);
  }

  // Perhaps move to HealthRecord.java
  /**
   * Determines what entry type (Immunization/Encounter/Procedure/Medication) of the given entry.
   *
   * @param entry the entry to parse.
   */
  private String getEntryType(Entry entry) {

    String entryType;

    if (entry instanceof Encounter) {
      entryType = HealthRecord.ENCOUNTERS;
    } else if (entry instanceof Medication) {
      entryType = HealthRecord.MEDICATIONS;
    } else if (entry instanceof Procedure) {
      entryType = HealthRecord.PROCEDURES;
    } else if (entry instanceof Immunization) {
      entryType = HealthRecord.IMMUNIZATIONS;
    } else {
      // Not an entry with a cost.
      entryType = "no_cost";
    }
    return entryType;
  }

  /**
   * Increments encounter utilization for a given year and encounter type.
   *
   * @param year the year of the encounter to add
   * @param key the key (the encounter type and whether it was covered/uncovered)
   */
  private synchronized void incrementEntries(Integer year, String key) {
    if (!entryUtilization.contains(year, key)) {
      entryUtilization.put(year, key, new AtomicInteger(0));
    }
    entryUtilization.get(year, key).incrementAndGet();
  }

  /**
   * Increases the total costs incurred by the payer by the given amount.
   *
   * @param costToPayer the cost of the current encounter, after the patient's copay.
   */
  public void addCoveredCost(BigDecimal costToPayer) {
    this.costsCovered = this.costsCovered.add(costToPayer);
  }

  /**
   * Increases the costs the payer did not cover by the given amount.
   *
   * @param costToPatient the costs that the payer did not cover.
   */
  public void addUncoveredCost(BigDecimal costToPatient) {
    this.costsUncovered = this.costsUncovered.add(costToPatient);
  }

  /**
   * Adds the Quality of Life Score (QOLS) of a patient of the current (past?)
   * year. Increments the total number of years covered (for averaging out
   * purposes).
   *
   * @param qols the Quality of Life Score to be added.
   */
  public void addQols(double qols) {
    this.totalQOLS += qols;
  }

  /**
   * Returns the total amount of money received from patients.
   * Consists of monthly premium payments.
   */
  public BigDecimal getRevenue() {
    return this.revenue;
  }

  /**
   * Returns the number of years the given customer was with this Payer.
   */
  public int getCustomerUtilization(Person person) {
    String personId = (String) person.attributes.get(Person.ID);
    if (!customerUtilization.containsKey(personId)) {
      return 0;
    }
    return customerUtilization.get(personId).get();
  }

  /**
   * Returns the total number of unique customers of this payer.
   */
  public int getUniqueCustomers() {
    return customerUtilization.size();
  }

  /**
   * Returns the total number of member years covered by this payer.
   */
  public int getNumYearsCovered() {
    return this.customerUtilization.values().stream().mapToInt(AtomicInteger::intValue).sum();
  }

  /**
   * Returns the number of encounters this payer paid for.
   */
  public int getEncountersCoveredCount() {
    return entryUtilization.column("covered-"
        + HealthRecord.ENCOUNTERS).values().stream().mapToInt(ai -> ai.get()).sum();
  }

  /**
   * Returns the number of encounters this payer did not cover for their customers.
   */
  public int getEncountersUncoveredCount() {
    return entryUtilization.column("uncovered-"
        + HealthRecord.ENCOUNTERS).values().stream().mapToInt(ai -> ai.get()).sum();
  }

  /**
   * Returns the number of medications this payer paid for.
   */
  public int getMedicationsCoveredCount() {
    return entryUtilization.column("covered-"
        + HealthRecord.MEDICATIONS).values().stream().mapToInt(ai -> ai.get()).sum();
  }

  /**
   * Returns the number of medications this payer did not cover for their customers.
   */
  public int getMedicationsUncoveredCount() {
    return entryUtilization.column("uncovered-"
        + HealthRecord.MEDICATIONS).values().stream().mapToInt(ai -> ai.get()).sum();
  }

  /**
   * Returns the number of procedures this payer paid for.
   */
  public int getProceduresCoveredCount() {
    return entryUtilization.column("covered-"
        + HealthRecord.PROCEDURES).values().stream().mapToInt(ai -> ai.get()).sum();
  }

  /**
   * Returns the number of procedures this payer did not cover for their customers.
   */
  public int getProceduresUncoveredCount() {
    return entryUtilization.column("uncovered-"
        + HealthRecord.PROCEDURES).values().stream().mapToInt(ai -> ai.get()).sum();
  }

  /**
   * Returns the number of immunizations this payer paid for.
   */
  public int getImmunizationsCoveredCount() {
    return entryUtilization.column("covered-"
        + HealthRecord.IMMUNIZATIONS).values().stream().mapToInt(ai -> ai.get()).sum();
  }

  /**
   * Returns the number of immunizations this payer did not cover for their customers.
   */
  public int getImmunizationsUncoveredCount() {
    return entryUtilization.column("uncovered-"
        + HealthRecord.IMMUNIZATIONS).values().stream().mapToInt(ai -> ai.get()).sum();
  }

  /**
   * Returns the amount of money the payer paid to providers.
   */
  public BigDecimal getAmountCovered() {
    return this.costsCovered;
  }

  /**
   * Returns the amount of money the payer did not cover.
   */
  public BigDecimal getAmountUncovered() {
    return this.costsUncovered;
  }

  /**
   * Returns the average of the payer's QOLS of customers over the number of years covered.
   */
  public double getQolsAverage() {
    int numYears = this.getNumYearsCovered();
    return this.totalQOLS / numYears;
  }

  @Override
  public int hashCode() {
    int hash = 7;
    hash = 53 * hash + Objects.hashCode(this.attributes);
    hash = 53 * hash + Objects.hashCode(this.name);
    hash = 53 * hash + Objects.hashCode(this.uuid);
    hash = 53 * hash + Objects.hashCode(this.ownership);
    hash = 53 * hash + Objects.hashCode(this.statesCovered);
    hash = 53 * hash + this.revenue.hashCode();
    hash = 53 * hash + this.costsCovered.hashCode();
    hash = 53 * hash + this.costsUncovered.hashCode();
    hash = 53 * hash + (int) (Double.doubleToLongBits(this.totalQOLS)
            ^ (Double.doubleToLongBits(this.totalQOLS) >>> 32));
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final Payer other = (Payer) obj;
    if (!this.revenue.equals(other.revenue)) {
      return false;
    }
    if (!this.costsCovered.equals(other.costsCovered)) {
      return false;
    }
    if (!this.costsUncovered.equals(other.costsUncovered)) {
      return false;
    }
    if (Double.doubleToLongBits(this.totalQOLS)
            != Double.doubleToLongBits(other.totalQOLS)) {
      return false;
    }
    if (!Objects.equals(this.plans, other.plans)) {
      return false;
    }
    if (!Objects.equals(this.name, other.name)) {
      return false;
    }
    if (!Objects.equals(this.uuid, other.uuid)) {
      return false;
    }
    if (!Objects.equals(this.ownership, other.ownership)) {
      return false;
    }
    if (!Objects.equals(this.attributes, other.attributes)) {
      return false;
    }
    if (!Objects.equals(this.statesCovered, other.statesCovered)) {
      return false;
    }
    return true;
  }

  /**
   * Sets the payer claim adjustment strategy.
   * @param payerAdjustment The payer adjustment algorithm.
   */
  public void setPayerAdjustment(IPayerAdjustment payerAdjustment) {
    this.payerAdjustment = payerAdjustment;
  }

  public boolean isGovernmentPayer() {
    return this.ownership.equals(PayerManager.GOV_OWNERSHIP);
  }

  /**
   * Returns the insurance status that this payer would provide it's customers.
   * @return
   */
  public String getAssociatedInsuranceStatus() {
    if (this.isNoInsurance()) {
      return "none";
    }
    if (this.isGovernmentPayer()) {
      return this.name;
    }
    return "private";
  }

  /**
   * Adds the given revenue to the payer.
   * @param additionalRevenue The revenue to add.
   */
  public void addRevenue(BigDecimal additionalRevenue) {
    this.revenue = this.revenue.add(additionalRevenue);
  }

  /**
   * Returns the government payer plan if this is a government payer.
   * @return  This payer's government payer plan.
   */
  public InsurancePlan getGovernmentPayerPlan() {
    if (!this.ownership.equals(PayerManager.GOV_OWNERSHIP)) {
      throw new RuntimeException("Only government payers can call getGovernmentPayerPlan().");
    }
    return this.plans.iterator().next();
  }

  /**
   * Add additional attributes to Payer.
   */
  public void addAttribute(String key, String value) {
    this.attributes.put(key, value);
  }

  /**
   * Returns whether this is the no insurance payer.
   * @return
   */
  public boolean isNoInsurance() {
    return this.name.equals(PayerManager.NO_INSURANCE);
  }

  public String getPlanLinkId() {
    return this.planLinkId;
  }

}