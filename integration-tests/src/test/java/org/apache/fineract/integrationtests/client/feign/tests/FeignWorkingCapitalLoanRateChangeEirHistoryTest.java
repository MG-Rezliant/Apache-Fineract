/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests.client.feign.tests;

import static org.apache.fineract.integrationtests.client.feign.helpers.FeignWorkingCapitalLoanHelper.assertEqualBigDecimal;
import static org.apache.fineract.integrationtests.client.feign.modules.WorkingCapitalLoanRateHistoryValidators.entryByNewRate;
import static org.apache.fineract.integrationtests.client.feign.modules.WorkingCapitalLoanRateHistoryValidators.validateSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.fineract.client.models.GetWorkingCapitalLoansLoanIdResponse;
import org.apache.fineract.client.models.WorkingCapitalLoanPeriodPaymentRateChangeData;
import org.apache.fineract.integrationtests.client.FeignIntegrationTest;
import org.apache.fineract.integrationtests.client.feign.helpers.FeignBusinessDateHelper;
import org.apache.fineract.integrationtests.client.feign.helpers.FeignClientHelper;
import org.apache.fineract.integrationtests.client.feign.helpers.FeignWorkingCapitalLoanHelper;
import org.apache.fineract.integrationtests.client.feign.modules.WorkingCapitalLoanRequestBuilders;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.workingcapitalloanproduct.WorkingCapitalLoanProductHelper;
import org.apache.fineract.integrationtests.common.workingcapitalloanproduct.WorkingCapitalLoanProductTestBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * On each % payment rate change the system persists a history snapshot of the EIR and the other calculated values, and
 * returns it from fetch-loan-details. The loan-details business event carries the same history through the same read
 * service; that its payload is mapped field for field is covered by {@code WorkingCapitalLoanAccountDataMapperTest}.
 *
 * <p>
 * Base scenario: WC loan with TPV 100000, net disbursement 9000, discount 1000, NPV day count 360, rate 18% disbursed
 * 2019-01-01 → daily payment 50, total days 200, daily EIR 0.001067814488.
 *
 * <p>
 * The per-change SNAPSHOT values are the engine's own {@code RateSegment} numbers: the snapshot is written once
 * as-booked from what the engine computed for that change and is never restated afterwards — an order-dependent audit
 * trail.
 *
 * <p>
 * Annualized EIR is {@code (1+dailyEir)^npvDayCount - 1} over this product's 360-day count: the day count that prices
 * the daily payment is the one the year is measured in. Snapshot annual EIRs are asserted at the 6 decimals they are
 * persisted with, the derived account-level one at 9, daily EIR at 12, amounts at currency scale 2.
 */
public class FeignWorkingCapitalLoanRateChangeEirHistoryTest extends FeignIntegrationTest {

    // --- Base loan setup ---
    private static final String DISBURSE_DATE = "01 January 2019";
    private static final String CHANGE1_DATE = "25 January 2019";
    private static final String CHANGE2_DATE = "01 February 2019";
    private static final BigDecimal PRINCIPAL = BigDecimal.valueOf(9000);
    private static final BigDecimal DISCOUNT = BigDecimal.valueOf(1000);
    private static final BigDecimal RATE_18 = BigDecimal.valueOf(18);
    private static final BigDecimal RATE_17 = BigDecimal.valueOf(17);
    private static final BigDecimal RATE_16 = BigDecimal.valueOf(16);
    private static final BigDecimal RATE_20 = BigDecimal.valueOf(20);

    // --- Expected values: base schedule at 18% ---
    private static final String EIR_18 = "0.001067814488";
    // (1 + 0.001067814488)^360 - 1 = 0.468451024827 → 9 dp (derived on read, not persisted)
    private static final String ANNUAL_EIR_18 = "0.468451025";

    // --- Engine as-booked values: 17% change effective 2019-01-25, booked on that same day.
    // These scenarios never repay, so at the split the schedule has billed nothing: the segment re-amortizes the whole
    // outstanding 9000 balance plus the unearned 1000 discount at the new rate. totalDays = ceil(10000 / 47.22) = 212,
    // EIR = IRR over that segment. ---
    private static final String NET_DISB_17 = "9000.00";
    private static final String DISCOUNT_17 = "1000.00";
    private static final String DAILY_PAYMENT_17 = "47.22";
    private static final int TOTAL_DAYS_17 = 212;
    private static final String EIR_17_DAILY = "0.001008699894";
    // (1 + 0.001008699894)^360 - 1 → 6 dp
    private static final String ANNUAL_EIR_17 = "0.437562";

    // --- Engine as-booked values: the same 17% change booked five days EARLY (2019-01-20, effective 2019-01-25).
    // Booking date bounds how far the schedule is treated as having run, so the balance the segment continues from is
    // the one outstanding at 2019-01-20, not at the effective date - a different snapshot for the same rate change. ---
    private static final String NET_DISB_17_BOOKED_EARLY = "8797.62";
    private static final String DISCOUNT_17_BOOKED_EARLY = "952.38";
    private static final int TOTAL_DAYS_17_BOOKED_EARLY = 207;
    private static final String EIR_17_BOOKED_EARLY_DAILY = "0.001008705077";
    // (1 + 0.001008705077)^360 - 1 → 6 dp
    private static final String ANNUAL_EIR_17_BOOKED_EARLY = "0.437565";

    // --- Engine as-booked values: 20% change effective 2019-02-01 booked AFTER the 17% change. Nothing has been
    // repaid here either, so this segment too re-amortizes the full 9000 + 1000: totalDays = ceil(10000 / 55.56). ---
    private static final String NET_DISB_20_AFTER_17 = "9000.00";
    private static final String DISCOUNT_20_AFTER_17 = "1000.00";
    private static final String DAILY_PAYMENT_20 = "55.56";
    private static final int TOTAL_DAYS_20 = 180;
    private static final String EIR_20_AFTER_17_DAILY = "0.001185944716";
    // (1 + 0.001185944716)^360 - 1 → 6 dp
    private static final String ANNUAL_EIR_20_AFTER_17 = "0.532173";

    // --- The backdating scenario repays 1000 on 2019-01-28, BETWEEN the two changes. That is what makes the
    // booking order matter: the repayment is allocated by the schedule in force when it lands, so the balance and
    // unearned discount the 2019-02-01 segment continues from depend on whether the 17% change was already there.
    // With no repayment - or one before both changes - the two orders produce the same numbers and "as-booked, never
    // restated" cannot be told apart from its opposite. ---
    private static final BigDecimal REPAYMENT_AMOUNT = BigDecimal.valueOf(1000);
    private static final String REPAYMENT_DATE = "28 January 2019";

    // --- Engine as-booked values: 20% change effective 2019-02-01 booked AFTER the 17% change, with the repayment
    // allocated under the 17% schedule. This is what a replay produces for the segment - the value the backdated
    // loan's row would carry if the snapshot were restated. ---
    private static final String NET_DISB_20_AFTER_17_REPAID = "8194.24";
    private static final String DISCOUNT_20_AFTER_17_REPAID = "805.76";
    private static final int TOTAL_DAYS_20_REPAID = 162;
    private static final String EIR_20_AFTER_17_REPAID_DAILY = "0.001169946129";
    // (1 + 0.001169946129)^360 - 1 → 6 dp
    private static final String ANNUAL_EIR_20_AFTER_17_REPAID = "0.523384";

    // --- Engine as-booked values: the same 20% change booked ALONE against the 18% schedule, before the 17% one is
    // backdated in front of it - the repayment was allocated at 18%, leaving a different balance at the split. ---
    private static final String NET_DISB_20_AS_BOOKED_ALONE = "8183.96";
    private static final String DISCOUNT_20_AS_BOOKED_ALONE = "816.04";
    private static final String EIR_20_AS_BOOKED_ALONE_DAILY = "0.001185871519";
    // (1 + 0.001185871519)^360 - 1 → 6 dp
    private static final String ANNUAL_EIR_20_AS_BOOKED_ALONE = "0.532133";

    // --- Engine as-booked values: 16% same-date overwrite of the 17% change (same split, dailyPayment
    // = round(100000×16/360/100) = 44.44, totalDays = ceil(10000 / 44.44) = 226) ---
    private static final String DAILY_PAYMENT_16 = "44.44";
    private static final int TOTAL_DAYS_16 = 226;
    private static final String EIR_16_DAILY = "0.000949561758";
    // (1 + 0.000949561758)^360 - 1 → 6 dp
    private static final String ANNUAL_EIR_16 = "0.407310";

    // Effective term after the 17% (day 24) and 20% (day 31) changes → last payment 2019-07-30
    private static final int EFFECTIVE_TERM_AFTER_BOTH_CHANGES = 210;

    private FeignWorkingCapitalLoanHelper wcLoanHelper;
    private FeignClientHelper clientHelper;
    private FeignBusinessDateHelper businessDateHelper;
    private WorkingCapitalLoanProductHelper productHelper;

    private final List<Long> createdLoanIds = new ArrayList<>();

    @BeforeAll
    void setupHelpers() {
        wcLoanHelper = new FeignWorkingCapitalLoanHelper(fineractClient());
        clientHelper = new FeignClientHelper(fineractClient());
        businessDateHelper = new FeignBusinessDateHelper(fineractClient());
        productHelper = new WorkingCapitalLoanProductHelper();
    }

    @AfterAll
    void cleanupEntities() {
        createdLoanIds.forEach(wcLoanHelper::cleanupLoan);
        createdLoanIds.clear();
    }

    @Test
    @DisplayName("A single % payment rate change persists the EIR and calculated-values snapshot")
    void singleRateChange_persistsEirAndCalculatedValuesSnapshot() {
        businessDateHelper.runAt("2019-01-01", () -> {
            final Long loanId = createBaseLoan();
            applyRateChange(loanId, RATE_17, "2019-01-25", CHANGE1_DATE);

            final List<WorkingCapitalLoanPeriodPaymentRateChangeData> history = wcLoanHelper.getRateChangeHistory(loanId);
            assertEquals(1, history.size(), "One rate change must produce exactly one history entry");
            final WorkingCapitalLoanPeriodPaymentRateChangeData change = history.getFirst();
            assertEqualBigDecimal(RATE_18, change.getPreviousRate(), "previousRate");
            assertEqualBigDecimal(RATE_17, change.getNewRate(), "newRate");
            assertEquals(LocalDate.of(2019, 1, 25), change.getEffectiveDate(), "effectiveDate");
            assertFalse(change.getReversed(), "The only change must be active");
            validateSnapshot("17% change (2019-01-25)", change, EIR_17_DAILY, ANNUAL_EIR_17, NET_DISB_17, DISCOUNT_17, DAILY_PAYMENT_17,
                    TOTAL_DAYS_17);
        });
    }

    @Test
    @DisplayName("A second rate change adds a second snapshot and leaves the first snapshot unchanged")
    void secondRateChange_addsSecondSnapshotAndKeepsFirstUnchanged() {
        businessDateHelper.runAt("2019-01-01", () -> {
            final Long loanId = createBaseLoan();
            applyRateChange(loanId, RATE_17, "2019-01-25", CHANGE1_DATE);
            applyRateChange(loanId, RATE_20, "2019-02-01", CHANGE2_DATE);

            final List<WorkingCapitalLoanPeriodPaymentRateChangeData> history = wcLoanHelper.getRateChangeHistory(loanId);
            assertEquals(2, history.size(), "Two rate changes on different effective dates must both be recorded");
            assertTrue(history.stream().noneMatch(WorkingCapitalLoanPeriodPaymentRateChangeData::getReversed),
                    "Changes on different effective dates must both stay active");

            validateSnapshot("20% change (2019-02-01)", entryByNewRate(history, RATE_20), EIR_20_AFTER_17_DAILY, ANNUAL_EIR_20_AFTER_17,
                    NET_DISB_20_AFTER_17, DISCOUNT_20_AFTER_17, DAILY_PAYMENT_20, TOTAL_DAYS_20);
            validateSnapshot("17% change (2019-01-25) after the second change", entryByNewRate(history, RATE_17), EIR_17_DAILY,
                    ANNUAL_EIR_17, NET_DISB_17, DISCOUNT_17, DAILY_PAYMENT_17, TOTAL_DAYS_17);
        });
    }

    @Test
    @DisplayName("Fetch-loan-details returns the periodPaymentRateHistory with the persisted snapshots")
    void loanDetails_returnsPeriodPaymentRateHistoryWithSnapshots() {
        businessDateHelper.runAt("2019-01-01", () -> {
            final Long loanId = createBaseLoan();
            applyRateChange(loanId, RATE_17, "2019-01-25", CHANGE1_DATE);
            applyRateChange(loanId, RATE_20, "2019-02-01", CHANGE2_DATE);

            final List<WorkingCapitalLoanPeriodPaymentRateChangeData> history = wcLoanHelper.getLoanDetails(loanId)
                    .getPeriodPaymentRateHistory();
            assertNotNull(history, "Fetch-loan-details must return the periodPaymentRateHistory array");
            assertEquals(2, history.size(), "Fetch-loan-details must return both rate-change history entries");
            validateSnapshot("loan-details history, 17% change", entryByNewRate(history, RATE_17), EIR_17_DAILY, ANNUAL_EIR_17, NET_DISB_17,
                    DISCOUNT_17, DAILY_PAYMENT_17, TOTAL_DAYS_17);
            validateSnapshot("loan-details history, 20% change", entryByNewRate(history, RATE_20), EIR_20_AFTER_17_DAILY,
                    ANNUAL_EIR_20_AFTER_17, NET_DISB_20_AFTER_17, DISCOUNT_20_AFTER_17, DAILY_PAYMENT_20, TOTAL_DAYS_20);
        });
    }

    @Test
    @DisplayName("Top-level EIR fields in loan details keep the original-schedule semantics after rate changes")
    void loanDetails_topLevelEirFieldsKeepOriginalScheduleSemantics() {
        businessDateHelper.runAt("2019-01-01", () -> {
            final Long loanId = createBaseLoan();
            applyRateChange(loanId, RATE_17, "2019-01-25", CHANGE1_DATE);
            applyRateChange(loanId, RATE_20, "2019-02-01", CHANGE2_DATE);

            final GetWorkingCapitalLoansLoanIdResponse loan = wcLoanHelper.getLoanDetails(loanId);
            assertEqualBigDecimal(new BigDecimal(EIR_18), round(loan.getDailyEir(), 12), "top-level dailyEir (original schedule)");
            assertEqualBigDecimal(new BigDecimal(ANNUAL_EIR_18), round(loan.getCalculatedAnnualEir(), 9),
                    "top-level calculatedAnnualEir (original schedule, ^npvDayCount convention)");
            assertEqualBigDecimal(BigDecimal.valueOf(50), loan.getPeriodPaymentAmount(), "top-level periodPaymentAmount (original)");
            assertEqualBigDecimal(PRINCIPAL, loan.getNetDisbursalAmount(), "top-level netDisbursalAmount (original)");
            assertEquals(EFFECTIVE_TERM_AFTER_BOTH_CHANGES, loan.getNumberOfRepayments(),
                    "numberOfRepayments must stay segment-aware (last payment 2019-07-30)");
        });
    }

    @Test
    @DisplayName("A same-date overwrite keeps the reversed change's snapshot and computes a fresh one for the replacement")
    void sameDateOverwrite_keepsReversedSnapshotAndComputesFreshOne() {
        businessDateHelper.runAt("2019-01-01", () -> {
            final Long loanId = createBaseLoan();
            applyRateChange(loanId, RATE_17, "2019-01-25", CHANGE1_DATE);
            // overwrite on the same effective date: 17% is auto-reversed, 16% replaces it
            wcLoanHelper.updateRate(loanId, WorkingCapitalLoanRequestBuilders.updateRate(RATE_16, CHANGE1_DATE));

            final List<WorkingCapitalLoanPeriodPaymentRateChangeData> history = wcLoanHelper.getRateChangeHistory(loanId);
            assertEquals(2, history.size(), "The overwritten and the replacing change must both be recorded");
            final WorkingCapitalLoanPeriodPaymentRateChangeData overwritten = entryByNewRate(history, RATE_17);
            final WorkingCapitalLoanPeriodPaymentRateChangeData replacement = entryByNewRate(history, RATE_16);
            assertTrue(overwritten.getReversed(), "The overwritten same-date change must be reversed");
            assertFalse(replacement.getReversed(), "The replacing change must be active");

            validateSnapshot("reversed 17% change (as-booked snapshot retained)", overwritten, EIR_17_DAILY, ANNUAL_EIR_17, NET_DISB_17,
                    DISCOUNT_17, DAILY_PAYMENT_17, TOTAL_DAYS_17);
            validateSnapshot("replacing 16% change (fresh snapshot)", replacement, EIR_16_DAILY, ANNUAL_EIR_16, NET_DISB_17, DISCOUNT_17,
                    DAILY_PAYMENT_16, TOTAL_DAYS_16);
        });
    }

    @Test
    @DisplayName("A backdated rate change leaves the earlier-booked change's snapshot as-booked (audit trail, no restatement)")
    void backdatedRateChange_keepsEarlierBookedSnapshotAsBooked() {
        businessDateHelper.runAt("2019-01-01", () -> {
            // Two loans that end up with the same two changes and the same repayment, differing only in the order the
            // changes were booked. The one booked in order shows what a replay computes for the 2019-02-01 segment;
            // the backdated one must still report what was computed when its own 20% change was booked.
            final Long bookedInOrder = createBaseLoan();
            final Long bookedBackdated = createBaseLoan();

            applyRateChange(bookedInOrder, RATE_17, "2019-01-25", CHANGE1_DATE);
            businessDateHelper.updateBusinessDate("BUSINESS_DATE", "2019-01-28");
            wcLoanHelper.makeRepayment(bookedInOrder, WorkingCapitalLoanRequestBuilders.repayment(REPAYMENT_AMOUNT, REPAYMENT_DATE));
            wcLoanHelper.makeRepayment(bookedBackdated, WorkingCapitalLoanRequestBuilders.repayment(REPAYMENT_AMOUNT, REPAYMENT_DATE));

            businessDateHelper.updateBusinessDate("BUSINESS_DATE", "2019-02-01");
            wcLoanHelper.updateRate(bookedInOrder, WorkingCapitalLoanRequestBuilders.updateRate(RATE_20, CHANGE2_DATE));
            wcLoanHelper.updateRate(bookedBackdated, WorkingCapitalLoanRequestBuilders.updateRate(RATE_20, CHANGE2_DATE));
            // backdated: booked on 2019-02-01, effective 2019-01-25 — replayed before the 20% change in the schedule,
            // but the 20% row's persisted snapshot stays exactly as it was booked
            wcLoanHelper.updateRate(bookedBackdated, WorkingCapitalLoanRequestBuilders.updateRate(RATE_17, CHANGE1_DATE));

            final List<WorkingCapitalLoanPeriodPaymentRateChangeData> history = wcLoanHelper.getRateChangeHistory(bookedBackdated);
            assertEquals(2, history.size(), "Both changes must be recorded");
            assertTrue(history.stream().noneMatch(WorkingCapitalLoanPeriodPaymentRateChangeData::getReversed),
                    "Changes on different effective dates must both stay active");

            // The backdated change is snapshotted at its effective date, before the repayment: the same numbers as
            // booking it on the day, which the in-order loan asserts below.
            validateSnapshot("backdated 17% change (2019-01-25, booked at 2019-02-01)", entryByNewRate(history, RATE_17), EIR_17_DAILY,
                    ANNUAL_EIR_17, NET_DISB_17, DISCOUNT_17, DAILY_PAYMENT_17, TOTAL_DAYS_17);
            validateSnapshot("earlier-booked 20% change (2019-02-01) keeps its as-booked snapshot", entryByNewRate(history, RATE_20),
                    EIR_20_AS_BOOKED_ALONE_DAILY, ANNUAL_EIR_20_AS_BOOKED_ALONE, NET_DISB_20_AS_BOOKED_ALONE, DISCOUNT_20_AS_BOOKED_ALONE,
                    DAILY_PAYMENT_20, TOTAL_DAYS_20_REPAID);

            final List<WorkingCapitalLoanPeriodPaymentRateChangeData> replayedHistory = wcLoanHelper.getRateChangeHistory(bookedInOrder);
            final WorkingCapitalLoanPeriodPaymentRateChangeData replayed = entryByNewRate(replayedHistory, RATE_20);
            validateSnapshot("20% change (2019-02-01) booked after the 17% one", replayed, EIR_20_AFTER_17_REPAID_DAILY,
                    ANNUAL_EIR_20_AFTER_17_REPAID, NET_DISB_20_AFTER_17_REPAID, DISCOUNT_20_AFTER_17_REPAID, DAILY_PAYMENT_20,
                    TOTAL_DAYS_20_REPAID);
            validateSnapshot("17% change (2019-01-25) booked on the day", entryByNewRate(replayedHistory, RATE_17), EIR_17_DAILY,
                    ANNUAL_EIR_17, NET_DISB_17, DISCOUNT_17, DAILY_PAYMENT_17, TOTAL_DAYS_17);

            // The point of the scenario: what the backdated loan reports for its 20% change is not what its schedule
            // now computes for that segment. Were these ever to coincide the assertions above would stop guarding
            // anything, so the divergence is asserted rather than assumed.
            assertNotEquals(0, replayed.getEir().compareTo(entryByNewRate(history, RATE_20).getEir()),
                    "The 20% segment must compute differently with the 17% change replayed in front of it, otherwise this scenario "
                            + "cannot tell an as-booked snapshot from a restated one");
        });
    }

    @Test
    @DisplayName("A future-dated rate change snapshots its calculated values already at booking time")
    void futureDatedRateChange_snapshotsCalculatedValuesAtBookingTime() {
        businessDateHelper.runAt("2019-01-01", () -> {
            final Long loanId = createBaseLoan();
            // booked on 2019-01-20 for an effective date still five days out: the schedule is rebuilt straight away, so
            // the snapshot is available before the business step moves the loan's in-force rate on 2019-01-25
            businessDateHelper.updateBusinessDate("BUSINESS_DATE", "2019-01-20");
            wcLoanHelper.updateRate(loanId, WorkingCapitalLoanRequestBuilders.updateRate(RATE_17, CHANGE1_DATE));

            final List<WorkingCapitalLoanPeriodPaymentRateChangeData> history = wcLoanHelper.getRateChangeHistory(loanId);
            assertEquals(1, history.size(), "The future-dated change must be recorded straight away");
            final WorkingCapitalLoanPeriodPaymentRateChangeData change = history.getFirst();
            assertEquals(LocalDate.of(2019, 1, 25), change.getEffectiveDate(), "effectiveDate");
            assertFalse(change.getReversed(), "The only change must be active");
            validateSnapshot("future-dated 17% change (booked 2019-01-20, effective 2019-01-25)", change, EIR_17_BOOKED_EARLY_DAILY,
                    ANNUAL_EIR_17_BOOKED_EARLY, NET_DISB_17_BOOKED_EARLY, DISCOUNT_17_BOOKED_EARLY, DAILY_PAYMENT_17,
                    TOTAL_DAYS_17_BOOKED_EARLY);
        });
    }

    /** Advances the business date to the change day and books the rate change effective that day. */
    private void applyRateChange(final Long loanId, final BigDecimal newRate, final String isoBusinessDate, final String effectiveDate) {
        businessDateHelper.updateBusinessDate("BUSINESS_DATE", isoBusinessDate);
        wcLoanHelper.updateRate(loanId, WorkingCapitalLoanRequestBuilders.updateRate(newRate, effectiveDate));
    }

    /**
     * The base loan: TPV 100000 (builder default), principal/net disbursement 9000, discount 1000, rate 18%,
     * npvDayCount 360 (product default), submitted/approved/disbursed 2019-01-01. No repayments — all expected values
     * derive from the expected track only.
     */
    private Long createBaseLoan() {
        final Long testClientId = clientHelper.createClient(DISBURSE_DATE);
        final Long productId = createProduct();
        final Long loanId = wcLoanHelper.submitApplication(WorkingCapitalLoanRequestBuilders
                .submitApplication(testClientId, productId, PRINCIPAL, RATE_18, DISBURSE_DATE, DISBURSE_DATE).discount(DISCOUNT));
        createdLoanIds.add(loanId);
        wcLoanHelper.approve(loanId,
                WorkingCapitalLoanRequestBuilders.approveWithDiscount(DISBURSE_DATE, PRINCIPAL, DISBURSE_DATE, DISCOUNT));
        wcLoanHelper.disburse(loanId, WorkingCapitalLoanRequestBuilders.disburseWithDiscount(DISBURSE_DATE, PRINCIPAL, DISCOUNT));
        return loanId;
    }

    private Long createProduct() {
        final String uniqueName = "WCL EirHist " + Utils.uniqueRandomStringGenerator("", 8);
        final String uniqueShortName = Utils.uniqueRandomStringGenerator("", 4);
        // discountDefault override lets the application state the discount of 1000 explicitly
        return productHelper
                .createWorkingCapitalLoanProduct(new WorkingCapitalLoanProductTestBuilder().withName(uniqueName)
                        .withShortName(uniqueShortName).withAllowAttributeOverrides(Map.of("discountDefault", Boolean.TRUE)).build())
                .getResourceId();
    }

    private static BigDecimal round(final BigDecimal value, final int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }
}
