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
package org.apache.fineract.integrationtests.client.feign;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.client.feign.FineractFeignClient;
import org.apache.fineract.client.models.WorkingCapitalLoanBreachActionData;
import org.apache.fineract.client.models.WorkingCapitalLoanBreachScheduleData;
import org.apache.fineract.integrationtests.client.FeignIntegrationTest;
import org.apache.fineract.integrationtests.client.feign.helpers.FeignBusinessDateHelper;
import org.apache.fineract.integrationtests.client.feign.helpers.FeignClientHelper;
import org.apache.fineract.integrationtests.client.feign.helpers.FeignWorkingCapitalLoanHelper;
import org.apache.fineract.integrationtests.client.feign.modules.WorkingCapitalLoanRequestBuilders;
import org.apache.fineract.integrationtests.common.FineractFeignClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.workingcapitalloanbreach.WorkingCapitalBreachHelper;
import org.apache.fineract.integrationtests.common.workingcapitalloanproduct.WorkingCapitalLoanProductHelper;
import org.apache.fineract.integrationtests.common.workingcapitalloanproduct.WorkingCapitalLoanProductTestBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Domain test base for Working Capital loan integration tests (Feign structure, layer 2).
 *
 * <p>
 * Composes the Working Capital helpers (loan lifecycle, breach configuration/product creation, breach actions and
 * schedule reads, inline WC COB, business-date control) and exposes delegation methods so test classes never touch
 * helpers or construct requests directly. Created loans are tracked and cleaned up best-effort after the class.
 */
public abstract class FeignWorkingCapitalTestBase extends FeignIntegrationTest {

    protected static final BigDecimal DEFAULT_PERIOD_PAYMENT_RATE = BigDecimal.valueOf(18);

    protected static FeignWorkingCapitalLoanHelper wcLoanHelper;
    protected static FeignClientHelper clientHelper;
    protected static FeignBusinessDateHelper businessDateHelper;
    protected static WorkingCapitalLoanProductHelper productHelper;
    protected static WorkingCapitalBreachHelper breachHelper;

    private final List<Long> createdWcLoanIds = new ArrayList<>();

    @BeforeAll
    public static void setupWorkingCapitalHelpers() {
        final FineractFeignClient feignClient = FineractFeignClientHelper.getFineractFeignClient();
        wcLoanHelper = new FeignWorkingCapitalLoanHelper(feignClient);
        clientHelper = new FeignClientHelper(feignClient);
        businessDateHelper = new FeignBusinessDateHelper(feignClient);
        productHelper = new WorkingCapitalLoanProductHelper();
        breachHelper = new WorkingCapitalBreachHelper();
    }

    @AfterAll
    public void cleanupWorkingCapitalLoans() {
        createdWcLoanIds.forEach(wcLoanHelper::cleanupLoan);
        createdWcLoanIds.clear();
    }

    // --- Business date control ---

    /** Enables the business date, sets it to {@code isoDate} (yyyy-MM-dd), runs the action, then disables it. */
    protected void runAt(String isoDate, Runnable action) {
        businessDateHelper.runAt(isoDate, action);
    }

    /** Moves the business date to {@code isoDate} (yyyy-MM-dd). Use inside {@link #runAt(String, Runnable)}. */
    protected void setBusinessDate(String isoDate) {
        businessDateHelper.updateBusinessDate("BUSINESS_DATE", isoDate);
    }

    /**
     * Maximum number of skipped business days a single inline WC COB request is allowed to catch up on. The inline WC
     * COB replays every skipped day inside one HTTP request (~1s/day locally), so leaping the business date 40-60+ days
     * and then running COB once blows the Feign client's 180s read timeout. Chunking keeps every COB request bounded
     * and fast.
     */
    private static final int MAX_COB_CATCH_UP_DAYS = 14;

    /**
     * Advances the business date from {@code fromIso} to {@code toIso} (both yyyy-MM-dd) in bounded chunks, running the
     * inline WC COB for the given loan after each step so that no single COB request has to catch up more than
     * {@link #MAX_COB_CATCH_UP_DAYS} days. Always lands exactly on {@code toIso} and runs COB there, so actions
     * executed right after this call happen ON that exact business date with the schedule generated through it.
     *
     * <p>
     * The intermediate COB runs are state-idempotent for breach schedules: on days inside an open period they neither
     * generate periods nor (re-)evaluate breaches, so the final state is identical to a single COB on {@code toIso}.
     */
    protected void advanceBusinessDateWithCob(Long loanId, String fromIso, String toIso) {
        LocalDate current = LocalDate.parse(fromIso);
        final LocalDate target = LocalDate.parse(toIso);
        if (!target.isAfter(current)) {
            throw new IllegalArgumentException("advanceBusinessDateWithCob: target " + target + " must be after " + current);
        }
        while (current.isBefore(target)) {
            final LocalDate next = current.plusDays(MAX_COB_CATCH_UP_DAYS);
            current = next.isBefore(target) ? next : target;
            setBusinessDate(current.toString());
            runInlineWcCob(loanId);
        }
    }

    // --- Client / product / loan setup ---

    protected Long createClient(String activationDate) {
        return clientHelper.createClient(activationDate);
    }

    /**
     * Creates a Working Capital breach configuration and a product referencing it.
     *
     * @param breachFrequency
     *            breach period length (e.g. 60)
     * @param breachFrequencyType
     *            DAYS / WEEKS / MONTHS
     * @param breachAmountCalculationType
     *            FLAT / PERCENTAGE
     * @param breachAmount
     *            flat amount or percentage of principal
     * @param breachGraceDays
     *            grace days before the first breach period starts (0 = periods start on disbursement date)
     */
    protected Long createWcProductWithBreachConfig(int breachFrequency, String breachFrequencyType, String breachAmountCalculationType,
            BigDecimal breachAmount, int breachGraceDays) {
        final Long breachId = breachHelper.create(breachHelper.createBreachRequest(Utils.randomStringGenerator("WC_BREACH_", 8),
                breachFrequency, breachFrequencyType, breachAmountCalculationType, breachAmount));
        return productHelper.createWorkingCapitalLoanProduct(new WorkingCapitalLoanProductTestBuilder()
                .withName("WCL Breach " + Utils.uniqueRandomStringGenerator("", 8)).withShortName(Utils.uniqueRandomStringGenerator("", 4))
                .withBreachId(breachId).withBreachGraceDays(breachGraceDays).build()).getResourceId();
    }

    /** Submits, approves and disburses a Working Capital loan on the given date (dd MMMM yyyy). */
    protected Long createApproveAndDisburseWcLoan(Long clientId, Long productId, BigDecimal principal, String date) {
        final Long loanId = wcLoanHelper.submitApplication(WorkingCapitalLoanRequestBuilders.submitApplication(clientId, productId,
                principal, DEFAULT_PERIOD_PAYMENT_RATE, date, date));
        createdWcLoanIds.add(loanId);
        wcLoanHelper.approve(loanId, WorkingCapitalLoanRequestBuilders.approve(date, principal, date));
        wcLoanHelper.disburse(loanId, WorkingCapitalLoanRequestBuilders.disburse(date, principal));
        return loanId;
    }

    // --- Transactions & COB ---

    protected Long makeWcRepayment(Long loanId, BigDecimal amount, String transactionDate) {
        return wcLoanHelper.makeRepayment(loanId, WorkingCapitalLoanRequestBuilders.repayment(amount, transactionDate));
    }

    /** Runs the inline WC loan COB job for a single loan (generates/evaluates breach periods up to business date). */
    protected void runInlineWcCob(Long loanId) {
        wcLoanHelper.executeInlineWCCOB(loanId);
    }

    // --- Breach actions ---

    /** Plain breach reset on the current business date (no period restart). */
    protected Long createBreachReset(Long loanId) {
        return wcLoanHelper.createBreachAction(loanId, WorkingCapitalLoanRequestBuilders.breachReset());
    }

    /** Breach reset on the current business date with {@code restartPeriodFromResetDate = true} (splits the period). */
    protected Long createBreachResetWithRestartPeriod(Long loanId) {
        return wcLoanHelper.createBreachAction(loanId, WorkingCapitalLoanRequestBuilders.breachResetWithRestartPeriod());
    }

    /** Undoes the latest still-active breach reset on the current business date. */
    protected Long createBreachUndoReset(Long loanId) {
        return wcLoanHelper.createBreachAction(loanId, WorkingCapitalLoanRequestBuilders.breachUndoReset());
    }

    /** Creates a breach pause between the two dates (dd MMMM yyyy). */
    protected Long createBreachPause(Long loanId, String startDate, String endDate) {
        return wcLoanHelper.createBreachAction(loanId, WorkingCapitalLoanRequestBuilders.breachPause(startDate, endDate));
    }

    // --- Reads ---

    protected List<WorkingCapitalLoanBreachScheduleData> getBreachSchedule(Long loanId) {
        return wcLoanHelper.getBreachSchedule(loanId);
    }

    protected List<WorkingCapitalLoanBreachActionData> getBreachActions(Long loanId) {
        return wcLoanHelper.getBreachActions(loanId);
    }

    protected BigDecimal getBreachPastDueAmount(Long loanId) {
        return wcLoanHelper.getBreachPastDueAmount(loanId);
    }
}
