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

import static org.apache.fineract.integrationtests.client.feign.modules.WorkingCapitalBreachTestValidators.ExpectedBreachAction.action;
import static org.apache.fineract.integrationtests.client.feign.modules.WorkingCapitalBreachTestValidators.ExpectedBreachPeriod.period;
import static org.apache.fineract.integrationtests.client.feign.modules.WorkingCapitalBreachTestValidators.validateBreachActions;
import static org.apache.fineract.integrationtests.client.feign.modules.WorkingCapitalBreachTestValidators.validateBreachPastDueAmount;
import static org.apache.fineract.integrationtests.client.feign.modules.WorkingCapitalBreachTestValidators.validateBreachSchedule;

import java.math.BigDecimal;
import org.apache.fineract.integrationtests.client.feign.FeignWorkingCapitalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Undo of a working-capital breach reset that split the current breach period.
 *
 * <p>
 * When a breach reset performed with {@code restartPeriodFromResetDate = true} actually split the current breach
 * period, undoing that reset must restore the pre-split period (natural boundary, pauses re-applied), delete the
 * split-created periods, regenerate the schedule through the business date and reprocess the payments on it, and
 * recalculate the breach past-due amount.
 *
 * <p>
 * Common setup for every test: breach config 60 DAYS / PERCENTAGE 50, breach grace days 0; principal 800 disbursed 01
 * Jan 2026 → minimum payment 400 (50% of 800); repayments 200 on 15 Jan and 100 on 15 Feb; COB after 01 Mar → period 1
 * [2026-01-01..2026-03-01] breach=true outstanding=100, period 2 [2026-03-02..2026-04-30].
 */
public class FeignWorkingCapitalLoanBreachResetUndoTest extends FeignWorkingCapitalTestBase {

    private static final int BREACH_FREQUENCY = 60;
    private static final String BREACH_FREQUENCY_TYPE = "DAYS";
    private static final String BREACH_AMOUNT_CALCULATION_TYPE = "PERCENTAGE";
    private static final BigDecimal BREACH_AMOUNT_PERCENT = BigDecimal.valueOf(50);
    private static final int BREACH_GRACE_DAYS = 0;

    private static final BigDecimal PRINCIPAL = BigDecimal.valueOf(800);

    /**
     * A same-day undo of a restart-period reset reverts the split.
     *
     * <p>
     * Reset with restart on 15 Apr splits period 2 into [03-02..04-14] + [04-15..06-13]. The undo on the same day must
     * restore the 2-row schedule with period 2 back at its natural boundary [2026-03-02..2026-04-30] (60 days) and
     * bring the breach past-due amount from 0 back to 100 (period 1's outstanding).
     */
    @Test
    @DisplayName("Undo of a period-splitting reset on the same day restores the pre-split period and past due 100")
    void undoSameDay_revertsSplitPeriod_restoresTwoRowSchedule() {
        runAt("2026-01-01", () -> {
            final Long loanId = setupCommonBreachLoan();

            advanceBusinessDateWithCob(loanId, "2026-02-15", "2026-03-03");
            advanceBusinessDateWithCob(loanId, "2026-03-03", "2026-04-15");
            createBreachResetWithRestartPeriod(loanId);

            // Split state produced by the restart-period reset — precondition for the undo under test.
            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-04-14", 44, "400.00", "400.00", true, false), //
                    period(3, "2026-04-15", "2026-06-13", 60, "400.00", "400.00", null, true));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "0");

            // Undo same day: the split must be reverted, not just the reset flag lifted.
            createBreachUndoReset(loanId);

            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-04-30", 60, "400.00", "400.00", null, false));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "100");
            validateBreachActions(getBreachActions(loanId), //
                    action("RESET", "2026-04-15"), //
                    action("UNDO_RESET", "2026-04-15"));
        });
    }

    /**
     * Undo after later periods were generated by COB, with a payment inside the split-created period being reprocessed
     * into the regenerated boundaries.
     *
     * <p>
     * After the 15 Apr split, COB on 14 Jun chains period 4 off the split period, and a backdated 150 repayment (20
     * May) lands in split period 3. The undo on 20 Jun must delete BOTH split-descended periods (3 and 4), restore
     * period 2 to [03-02..04-30], regenerate period 3 as [2026-05-01..2026-06-29] and replay the 150 payment into it
     * (outstanding 250.00), and recompute past due to 500 (period 1's 100 + restored period 2's 400).
     */
    @Test
    @DisplayName("Undo after later periods restores the split, deletes chained periods and reprocesses the 150 payment")
    void undoAfterLaterPeriods_restoresSplitAndReprocessesPayments() {
        runAt("2026-01-01", () -> {
            final Long loanId = setupCommonBreachLoan();

            advanceBusinessDateWithCob(loanId, "2026-02-15", "2026-03-03");
            advanceBusinessDateWithCob(loanId, "2026-03-03", "2026-04-15");
            createBreachResetWithRestartPeriod(loanId);
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "0");

            // COB through 14 Jun chains period 4 off the split-created period 3, then a backdated repayment lands in
            // period 3.
            advanceBusinessDateWithCob(loanId, "2026-04-15", "2026-06-14");
            makeWcRepayment(loanId, BigDecimal.valueOf(150), "20 May 2026");

            // Pre-undo state: split intact, payment in split period 3, period 4 capped at the 350 still owed.
            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-04-14", 44, "400.00", "400.00", true, false), //
                    period(3, "2026-04-15", "2026-06-13", 60, "400.00", "250.00", true, true), //
                    period(4, "2026-06-14", "2026-08-12", 60, "350.00", "350.00", null, false));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "250");

            // Undo on 20 Jun: split reverted, periods regenerated from the restored boundary, payment reprocessed.
            advanceBusinessDateWithCob(loanId, "2026-06-14", "2026-06-20");
            createBreachUndoReset(loanId);

            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-04-30", 60, "400.00", "400.00", true, false), //
                    period(3, "2026-05-01", "2026-06-29", 60, "400.00", "250.00", null, false));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "500");
            validateBreachActions(getBreachActions(loanId), //
                    action("RESET", "2026-04-15"), //
                    action("UNDO_RESET", "2026-06-20"));
        });
    }

    /**
     * Boundary-crossing proof — a payment sitting in the split-created period BEFORE the restored natural boundary must
     * be reassigned into the restored pre-split period by the undo.
     *
     * <p>
     * The 15 Apr restart-reset truncates period 2 to [2026-03-02..2026-04-14] and split-creates period 3 from
     * 2026-04-15. A 150 repayment on 20 Apr therefore allocates to split period 3 (outstanding 250.00). The undo on 20
     * Apr restores period 2 to its natural boundary [2026-03-02..2026-04-30] — 20 Apr now falls INSIDE restored period
     * 2, so the payment must cross the restored boundary: restored period 2 outstanding 250.00 (base min payment 400
     * minus the 150), exactly 2 rows remain (the split-created period is deleted, no regeneration needed — the business
     * date 20 Apr is inside restored period 2), and past due returns to 100 (period 1's outstanding only).
     *
     * <p>
     * This is the assignment-crossing case the later-periods test does not cover: there the 20 May payment sits in
     * period 3 both before and after the undo; here the SAME payment moves from period 3 to period 2.
     */
    @Test
    @DisplayName("Undo reassigns a 20 Apr payment from the split-created period into the restored period 2")
    void undoReassignsPaymentAcrossRestoredBoundary_intoRestoredPeriodTwo() {
        runAt("2026-01-01", () -> {
            final Long loanId = setupCommonBreachLoan();

            advanceBusinessDateWithCob(loanId, "2026-02-15", "2026-03-03");
            advanceBusinessDateWithCob(loanId, "2026-03-03", "2026-04-15");
            createBreachResetWithRestartPeriod(loanId);
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "0");

            // Payment between the reset date (15 Apr) and the natural boundary (30 Apr): lands in split period 3.
            advanceBusinessDateWithCob(loanId, "2026-04-15", "2026-04-20");
            makeWcRepayment(loanId, BigDecimal.valueOf(150), "20 April 2026");

            // Pre-undo: split intact, the 150 sits in the split-created period 3, truncated period 2 untouched at 400.
            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-04-14", 44, "400.00", "400.00", true, false), //
                    period(3, "2026-04-15", "2026-06-13", 60, "400.00", "250.00", null, true));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "0");

            // Undo on 20 Apr: the 20 Apr payment must be reassigned into restored period 2, not vanish with the
            // deleted split period.
            createBreachUndoReset(loanId);

            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-04-30", 60, "400.00", "250.00", null, false));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "100");
            validateBreachActions(getBreachActions(loanId), //
                    action("RESET", "2026-04-15"), //
                    action("UNDO_RESET", "2026-04-20"));
        });
    }

    /**
     * The restored pre-split period is pause-aware.
     *
     * <p>
     * A breach pause 20–29 Mar extends period 2 to [2026-03-02..2026-05-10] (70 days). The 15 Apr restart-reset splits
     * it into [03-02..04-14] (44 days) + [04-15..06-13]. The undo must restore period 2 with the pause RE-APPLIED —
     * back to [2026-03-02..2026-05-10] and 70 days, not the natural 60-day [..2026-04-30] boundary.
     */
    @Test
    @DisplayName("Undo restores a pause-extended split period to 70 days with the pause re-applied")
    void undoRestoresPauseExtendedPeriod_withPauseReapplied() {
        runAt("2026-01-01", () -> {
            final Long loanId = setupCommonBreachLoan();

            advanceBusinessDateWithCob(loanId, "2026-02-15", "2026-03-03");

            advanceBusinessDateWithCob(loanId, "2026-03-03", "2026-03-20");
            createBreachPause(loanId, "20 March 2026", "29 March 2026");

            advanceBusinessDateWithCob(loanId, "2026-03-20", "2026-04-11");
            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-05-10", 70, "400.00", "400.00", null, false));

            advanceBusinessDateWithCob(loanId, "2026-04-11", "2026-04-15");
            createBreachResetWithRestartPeriod(loanId);
            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-04-14", 44, "400.00", "400.00", true, false), //
                    period(3, "2026-04-15", "2026-06-13", 60, "400.00", "400.00", null, true));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "0");

            // Undo same day: period 2 must come back pause-extended (70 days), not at the natural 60-day boundary.
            createBreachUndoReset(loanId);

            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-05-10", 70, "400.00", "400.00", null, false));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "100");
            validateBreachActions(getBreachActions(loanId), //
                    action("PAUSE", "2026-03-20"), //
                    action("RESET", "2026-04-15"), //
                    action("UNDO_RESET", "2026-04-15"));
        });
    }

    /**
     * Stacked resets — the undo pops only the LIFO-latest (splitting) reset and preserves the earlier still-active
     * reset's flag and past-due anchoring.
     *
     * <p>
     * A plain reset on 20 Feb (inside period 1) marks period 1 reset=true. A second, restart-period reset on 15 Apr
     * splits period 2. The undo on 15 Apr must revert only the split: period 1 keeps reset=true (past due stays
     * anchored on it → 100), period 2 is restored to [2026-03-02..2026-04-30].
     */
    @Test
    @DisplayName("Undo of a split reset pops only the latest reset and preserves the earlier active reset flag")
    void undoOfStackedResets_popsOnlyLatest_preservesEarlierReset() {
        runAt("2026-01-01", () -> {
            final Long loanId = setupCommonBreachLoan();

            // Earlier plain reset inside period 1's window (after both repayments).
            advanceBusinessDateWithCob(loanId, "2026-02-15", "2026-02-20");
            createBreachReset(loanId);

            advanceBusinessDateWithCob(loanId, "2026-02-20", "2026-03-03");
            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, true), //
                    period(2, "2026-03-02", "2026-04-30", 60, "400.00", "400.00", null, false));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "100");

            // Second reset with restart splits period 2.
            advanceBusinessDateWithCob(loanId, "2026-03-03", "2026-04-15");
            createBreachResetWithRestartPeriod(loanId);
            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, true), //
                    period(2, "2026-03-02", "2026-04-14", 44, "400.00", "400.00", true, false), //
                    period(3, "2026-04-15", "2026-06-13", 60, "400.00", "400.00", null, true));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "0");

            // Undo pops only reset #2: the split is reverted, period 1's earlier reset flag must survive.
            createBreachUndoReset(loanId);

            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, true), //
                    period(2, "2026-03-02", "2026-04-30", 60, "400.00", "400.00", null, false));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "100");
            validateBreachActions(getBreachActions(loanId), //
                    action("RESET", "2026-02-20"), //
                    action("RESET", "2026-04-15"), //
                    action("UNDO_RESET", "2026-04-15"));
        });
    }

    /**
     * Idempotence guard — a restart-period reset that caused NO split (reset date == the current period's fromDate)
     * must keep the flag-only undo behaviour: nothing is deleted, boundaries stay untouched, still exactly 2 rows.
     */
    @Test
    @DisplayName("Undo of a no-split restart reset is flag-only: schedule structure unchanged, past due 100")
    void undoOfNoSplitRestartReset_leavesScheduleStructureUnchanged() {
        runAt("2026-01-01", () -> {
            final Long loanId = setupCommonBreachLoan();

            // COB exactly on period 2's fromDate: the 02 Mar restart-reset then has resetDate == fromDate → no split.
            advanceBusinessDateWithCob(loanId, "2026-02-15", "2026-03-02");

            createBreachResetWithRestartPeriod(loanId);
            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-04-30", 60, "400.00", "400.00", null, true));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "0");

            // Undo same day: nothing to restore — only the reset flag is lifted, still exactly 2 periods.
            createBreachUndoReset(loanId);

            validateBreachSchedule(getBreachSchedule(loanId), //
                    period(1, "2026-01-01", "2026-03-01", 60, "400.00", "100.00", true, false), //
                    period(2, "2026-03-02", "2026-04-30", 60, "400.00", "400.00", null, false));
            validateBreachPastDueAmount(getBreachPastDueAmount(loanId), "100");
            validateBreachActions(getBreachActions(loanId), //
                    action("RESET", "2026-03-02"), //
                    action("UNDO_RESET", "2026-03-02"));
        });
    }

    /**
     * Common setup (business date must already be 2026-01-01): breach product 60 DAYS / PERCENTAGE 50 / grace 0, loan
     * of 800 submitted+approved+disbursed on 01 Jan 2026, first COB (creates period 1), repayment 200 on 15 Jan and 100
     * on 15 Feb. The business date is advanced in bounded COB chunks (the inline WC COB catches up every skipped day
     * inside one request, so unbounded leaps blow the client read timeout). Leaves the business date at 2026-02-15.
     */
    private Long setupCommonBreachLoan() {
        final Long clientId = createClient("01 January 2026");
        final Long productId = createWcProductWithBreachConfig(BREACH_FREQUENCY, BREACH_FREQUENCY_TYPE, BREACH_AMOUNT_CALCULATION_TYPE,
                BREACH_AMOUNT_PERCENT, BREACH_GRACE_DAYS);
        final Long loanId = createApproveAndDisburseWcLoan(clientId, productId, PRINCIPAL, "01 January 2026");
        runInlineWcCob(loanId);

        advanceBusinessDateWithCob(loanId, "2026-01-01", "2026-01-15");
        makeWcRepayment(loanId, BigDecimal.valueOf(200), "15 January 2026");

        advanceBusinessDateWithCob(loanId, "2026-01-15", "2026-02-15");
        makeWcRepayment(loanId, BigDecimal.valueOf(100), "15 February 2026");
        return loanId;
    }
}
