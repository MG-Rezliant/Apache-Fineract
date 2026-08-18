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
package org.apache.fineract.portfolio.workingcapitalloan.service;

import static org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType.BUSINESS_DATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.workingcapitalloan.data.TransactionDateAndAmountHolder;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoan;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBalance;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachAction;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachActionType;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachSchedule;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanPeriodFrequencyType;
import org.apache.fineract.portfolio.workingcapitalloan.mapper.WorkingCapitalLoanBreachScheduleMapper;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanBalanceRepository;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanBreachActionRepository;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanBreachScheduleRepository;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanRepository;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanTransactionRepository;
import org.apache.fineract.portfolio.workingcapitalloanbreach.domain.WorkingCapitalBreach;
import org.apache.fineract.portfolio.workingcapitalloanproduct.domain.WorkingCapitalBreachAmountCalculationType;
import org.apache.fineract.portfolio.workingcapitalloanproduct.domain.WorkingCapitalLoanProductRelatedDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkingCapitalLoanBreachScheduleServiceImplTest {

    private static final Long LOAN_ID = 1L;

    @Mock
    private WorkingCapitalLoanBreachScheduleRepository repository;

    @Mock
    private WorkingCapitalLoanBreachScheduleMapper mapper;

    @Mock
    private WorkingCapitalLoanRepository loanRepository;

    @Mock
    private WorkingCapitalLoanBreachActionRepository breachActionRepository;

    @Mock
    private WorkingCapitalLoanTransactionRepository transactionRepository;

    @Mock
    private WorkingCapitalLoanBalanceRepository balanceRepository;

    private WorkingCapitalLoanBreachScheduleServiceImpl underTest;

    private WorkingCapitalLoan loan;

    private WorkingCapitalLoanBalance balance;

    private FineractPlatformTenant originalTenant;

    @BeforeEach
    void setUp() {
        originalTenant = ThreadLocalContextUtil.getTenant();
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode("default", RoundingMode.HALF_UP.ordinal());
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BUSINESS_DATE, LocalDate.of(2026, 6, 1))));
        underTest = new WorkingCapitalLoanBreachScheduleServiceImpl(repository, mapper, loanRepository, breachActionRepository,
                transactionRepository, balanceRepository);
        loan = new WorkingCapitalLoan();
        loan.setId(LOAN_ID);
        balance = WorkingCapitalLoanBalance.createFor(loan);
        lenient().when(breachActionRepository.isBreachDisabledAsOf(anyLong(), any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.setTenant(originalTenant);
        MoneyHelper.clearCacheForTenant("default");
    }

    private WorkingCapitalLoanBreachSchedule period(final int periodNumber, final LocalDate fromDate, final LocalDate toDate,
            final BigDecimal minPaymentAmount, final BigDecimal paidAmount, final BigDecimal outstandingAmount) {
        final WorkingCapitalLoanBreachSchedule period = new WorkingCapitalLoanBreachSchedule();
        period.setLoan(loan);
        period.setPeriodNumber(periodNumber);
        period.setFromDate(fromDate);
        period.setToDate(toDate);
        period.setMinPaymentAmount(minPaymentAmount);
        period.setPaidAmount(paidAmount);
        period.setOutstandingAmount(outstandingAmount);
        return period;
    }

    @Test
    void recalculatePastDueAmount_sumsOutstandingAmountAcrossEndedPeriods() {
        final List<WorkingCapitalLoanBreachSchedule> periods = List.of(
                period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                        BigDecimal.ZERO),
                period(2, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20), BigDecimal.valueOf(100), BigDecimal.valueOf(40),
                        BigDecimal.valueOf(60)),
                period(3, LocalDate.of(2026, 5, 21), LocalDate.of(2026, 5, 31), BigDecimal.valueOf(100), BigDecimal.ZERO,
                        BigDecimal.valueOf(100)));
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(periods);
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.recalculatePastDueAmount(loan);

        assertEquals(0, BigDecimal.valueOf(160).compareTo(balance.getBreachPastDueAmount()));
    }

    @Test
    void recalculatePastDueAmount_settledPeriodsContributeZeroBalance() {
        final List<WorkingCapitalLoanBreachSchedule> periods = List.of(
                period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                        BigDecimal.ZERO),
                period(2, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                        BigDecimal.ZERO));
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(periods);
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.recalculatePastDueAmount(loan);

        assertEquals(0, BigDecimal.ZERO.compareTo(balance.getBreachPastDueAmount()));
    }

    @Test
    void recalculatePastDueAmount_excludesCurrentlyOpenPeriod() {
        final List<WorkingCapitalLoanBreachSchedule> periods = List.of(
                period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10), BigDecimal.valueOf(100), BigDecimal.ZERO,
                        BigDecimal.valueOf(100)),
                period(2, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 6, 20), BigDecimal.valueOf(50), BigDecimal.ZERO,
                        BigDecimal.valueOf(50)));
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(periods);
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.recalculatePastDueAmount(loan);

        assertEquals(0, BigDecimal.valueOf(100).compareTo(balance.getBreachPastDueAmount()));
    }

    @Test
    void recalculatePastDueAmount_startsFromLatestResetPeriod() {
        final WorkingCapitalLoanBreachSchedule priorPeriod = period(1, LocalDate.of(2026, 4, 20), LocalDate.of(2026, 4, 30),
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));
        final WorkingCapitalLoanBreachSchedule resetPeriod = period(2, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));
        resetPeriod.setReset(true);
        final WorkingCapitalLoanBreachSchedule normalPeriod = period(3, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20),
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(priorPeriod, resetPeriod, normalPeriod));
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.recalculatePastDueAmount(loan);

        assertEquals(0, BigDecimal.valueOf(200).compareTo(balance.getBreachPastDueAmount()));
    }

    @Test
    void recalculatePastDueAmount_noBalance_doesNothing() {
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.empty());

        underTest.recalculatePastDueAmount(loan);

        verify(repository, never()).findByLoanIdOrderByPeriodNumberAsc(anyLong());
        verify(balanceRepository, never()).saveAndFlush(any());
    }

    @Test
    void recalculatePastDueAmount_skipsWhenBreachEvaluationDisabled() {
        when(breachActionRepository.isBreachDisabledAsOf(LOAN_ID, LocalDate.of(2026, 6, 1))).thenReturn(true);

        underTest.recalculatePastDueAmount(loan);

        verify(balanceRepository, never()).findByWcLoan_Id(anyLong());
        verify(repository, never()).findByLoanIdOrderByPeriodNumberAsc(anyLong());
        verify(balanceRepository, never()).saveAndFlush(any());
    }

    @Test
    void applyRepayment_recalculatesPastDueAmountAfterUpdatingPeriod() {
        final LocalDate transactionDate = LocalDate.of(2026, 5, 15);
        final WorkingCapitalLoanBreachSchedule currentPeriod = period(2, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20),
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));
        when(repository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, transactionDate, transactionDate))
                .thenReturn(Optional.of(currentPeriod));
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(currentPeriod));
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.applyRepayment(LOAN_ID, transactionDate, BigDecimal.valueOf(60));

        assertEquals(0, BigDecimal.valueOf(40).compareTo(currentPeriod.getOutstandingAmount()));
        assertEquals(0, BigDecimal.valueOf(40).compareTo(balance.getBreachPastDueAmount()));
    }

    @Test
    void applyRepayment_updatesResetPeriod() {
        final LocalDate transactionDate = LocalDate.of(2026, 5, 15);
        final WorkingCapitalLoanBreachSchedule resetPeriod = period(1, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20),
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));
        resetPeriod.setReset(true);
        when(repository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, transactionDate, transactionDate))
                .thenReturn(Optional.of(resetPeriod));

        underTest.applyRepayment(LOAN_ID, transactionDate, BigDecimal.valueOf(60));

        assertEquals(0, BigDecimal.valueOf(60).compareTo(resetPeriod.getPaidAmount()));
        assertEquals(0, BigDecimal.valueOf(40).compareTo(resetPeriod.getOutstandingAmount()));
    }

    @Test
    void applyRepayment_skipsWhenBreachEvaluationDisabled() {
        final LocalDate transactionDate = LocalDate.of(2026, 5, 15);
        when(breachActionRepository.isBreachDisabledAsOf(LOAN_ID, LocalDate.of(2026, 6, 1))).thenReturn(true);

        underTest.applyRepayment(LOAN_ID, transactionDate, BigDecimal.valueOf(60));

        verify(repository, never()).findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(anyLong(), any(), any());
        verify(balanceRepository, never()).saveAndFlush(any());
    }

    @Test
    void applyRepaymentUndo_recalculatesPastDueAmountAfterUpdatingPeriod() {
        final LocalDate transactionDate = LocalDate.of(2026, 5, 15);
        final WorkingCapitalLoanBreachSchedule currentPeriod = period(2, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20),
                BigDecimal.valueOf(100), BigDecimal.valueOf(60), BigDecimal.valueOf(40));
        when(repository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, transactionDate, transactionDate))
                .thenReturn(Optional.of(currentPeriod));
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(currentPeriod));
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.applyRepaymentUndo(LOAN_ID, transactionDate, BigDecimal.valueOf(60));

        assertEquals(0, BigDecimal.ZERO.compareTo(currentPeriod.getPaidAmount()));
        assertEquals(0, BigDecimal.valueOf(100).compareTo(currentPeriod.getOutstandingAmount()));
        assertEquals(0, BigDecimal.valueOf(100).compareTo(balance.getBreachPastDueAmount()));
    }

    @Test
    void applyRepaymentUndo_reflipsBreachForAlreadyEndedPeriod() {
        final LocalDate transactionDate = LocalDate.of(2026, 5, 15);
        final WorkingCapitalLoanBreachSchedule endedPeriod = period(2, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20),
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.ZERO);
        endedPeriod.setBreach(false);
        when(repository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, transactionDate, transactionDate))
                .thenReturn(Optional.of(endedPeriod));
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(endedPeriod));
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.applyRepaymentUndo(LOAN_ID, transactionDate, BigDecimal.valueOf(100));

        assertEquals(0, BigDecimal.valueOf(100).compareTo(endedPeriod.getOutstandingAmount()));
        assertEquals(Boolean.TRUE, endedPeriod.getBreach());
    }

    @Test
    void applyRepaymentUndo_updatesResetPeriod() {
        final LocalDate transactionDate = LocalDate.of(2026, 5, 15);
        final WorkingCapitalLoanBreachSchedule resetPeriod = period(1, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20),
                BigDecimal.valueOf(100), BigDecimal.valueOf(60), BigDecimal.valueOf(40));
        resetPeriod.setReset(true);
        when(repository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, transactionDate, transactionDate))
                .thenReturn(Optional.of(resetPeriod));

        underTest.applyRepaymentUndo(LOAN_ID, transactionDate, BigDecimal.valueOf(60));

        assertEquals(0, BigDecimal.ZERO.compareTo(resetPeriod.getPaidAmount()));
        assertEquals(0, BigDecimal.valueOf(100).compareTo(resetPeriod.getOutstandingAmount()));
        assertEquals(Boolean.TRUE, resetPeriod.getBreach());
    }

    @Test
    void applyRepaymentUndo_skipsWhenBreachEvaluationDisabled() {
        final LocalDate transactionDate = LocalDate.of(2026, 5, 15);
        when(breachActionRepository.isBreachDisabledAsOf(LOAN_ID, LocalDate.of(2026, 6, 1))).thenReturn(true);

        underTest.applyRepaymentUndo(LOAN_ID, transactionDate, BigDecimal.valueOf(60));

        verify(repository, never()).findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(anyLong(), any(), any());
        verify(balanceRepository, never()).saveAndFlush(any());
    }

    @Test
    void recalculatePeriodsForPauses_recalculatesPastDueAmount() {
        final WorkingCapitalBreach breachConfig = new WorkingCapitalBreach();
        breachConfig.setBreachFrequency(7);
        breachConfig.setBreachFrequencyType(WorkingCapitalLoanPeriodFrequencyType.DAYS);
        breachConfig.setBreachAmountCalculationType(WorkingCapitalBreachAmountCalculationType.FLAT);
        breachConfig.setBreachAmount(BigDecimal.valueOf(100));
        final WorkingCapitalLoanProductRelatedDetails details = new WorkingCapitalLoanProductRelatedDetails();
        details.setBreach(breachConfig);
        loan.setLoanProductRelatedDetails(details);

        final WorkingCapitalLoanBreachSchedule onlyPeriod = period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(onlyPeriod));
        when(breachActionRepository.findByWorkingCapitalLoanIdOrderById(LOAN_ID)).thenReturn(List.of());
        when(breachActionRepository.findByWorkingCapitalLoanIdAndActionOrderByIdDesc(anyLong(), any())).thenReturn(List.of());
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.recalculatePeriodsForPauses(loan);

        assertEquals(0, BigDecimal.valueOf(100).compareTo(balance.getBreachPastDueAmount()));
    }

    @Test
    void reprocessBreachSchedule_bucketsTransactionsIntoTheirOwnPeriod() {
        final WorkingCapitalLoanBreachSchedule period1 = period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));
        final WorkingCapitalLoanBreachSchedule period2 = period(2, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20),
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));

        final TransactionDateAndAmountHolder txn = new TransactionDateAndAmountHolder(LocalDate.of(2026, 5, 5), BigDecimal.valueOf(100));

        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(period1, period2));
        when(transactionRepository.fetchTransactionDateAndAmount(anyLong(), any())).thenReturn(List.of(txn));
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.reprocessBreachSchedule(loan);

        assertEquals(0, BigDecimal.ZERO.compareTo(period1.getOutstandingAmount()));
        assertEquals(Boolean.FALSE, period1.getBreach());
        assertEquals(0, BigDecimal.valueOf(100).compareTo(period2.getOutstandingAmount()));
        assertEquals(Boolean.TRUE, period2.getBreach());

        assertEquals(0, BigDecimal.valueOf(100).compareTo(balance.getBreachPastDueAmount()));
    }

    @Test
    void reprocessBreachSchedule_recalculatesResetPeriods() {
        final WorkingCapitalLoanBreachSchedule resetPeriod = period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));
        resetPeriod.setReset(true);
        final WorkingCapitalLoanBreachSchedule normalPeriod = period(2, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20),
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100));

        final TransactionDateAndAmountHolder txn = new TransactionDateAndAmountHolder(LocalDate.of(2026, 5, 5), BigDecimal.valueOf(100));

        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(resetPeriod, normalPeriod));
        when(transactionRepository.fetchTransactionDateAndAmount(anyLong(), any())).thenReturn(List.of(txn));
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.reprocessBreachSchedule(loan);

        assertEquals(0, BigDecimal.ZERO.compareTo(resetPeriod.getOutstandingAmount()));
        assertEquals(0, BigDecimal.valueOf(100).compareTo(resetPeriod.getPaidAmount()));
        assertEquals(Boolean.FALSE, resetPeriod.getBreach());

        assertEquals(0, BigDecimal.valueOf(100).compareTo(balance.getBreachPastDueAmount()));
    }

    private void givenBreachConfig(final int frequency, final WorkingCapitalLoanPeriodFrequencyType frequencyType) {
        final WorkingCapitalBreach breachConfig = new WorkingCapitalBreach();
        breachConfig.setBreachFrequency(frequency);
        breachConfig.setBreachFrequencyType(frequencyType);
        breachConfig.setBreachAmountCalculationType(WorkingCapitalBreachAmountCalculationType.FLAT);
        breachConfig.setBreachAmount(BigDecimal.valueOf(100));
        final WorkingCapitalLoanProductRelatedDetails details = new WorkingCapitalLoanProductRelatedDetails();
        details.setBreach(breachConfig);
        loan.setLoanProductRelatedDetails(details);
        when(breachActionRepository.findByWorkingCapitalLoanIdAndActionOrderByIdDesc(anyLong(), any())).thenReturn(List.of());
    }

    private WorkingCapitalLoanBreachSchedule period(final int periodNumber, final LocalDate fromDate, final LocalDate toDate,
            final int numberOfDays) {
        final WorkingCapitalLoanBreachSchedule period = period(periodNumber, fromDate, toDate, BigDecimal.valueOf(100), BigDecimal.ZERO,
                BigDecimal.valueOf(100));
        period.setNumberOfDays(numberOfDays);
        return period;
    }

    private WorkingCapitalLoanBreachAction pause(final LocalDate startDate, final LocalDate endDate) {
        final WorkingCapitalLoanBreachAction pause = new WorkingCapitalLoanBreachAction();
        pause.setAction(WorkingCapitalLoanBreachActionType.PAUSE);
        pause.setStartDate(startDate);
        pause.setEndDate(endDate);
        return pause;
    }

    @Test
    void splitPeriodAtReset_truncatesTheLastPeriodAndOpensANewOneOnTheResetDate() {
        givenBreachConfig(7, WorkingCapitalLoanPeriodFrequencyType.DAYS);
        // The reset date is always the business date, which the schedule is generated up to.
        final WorkingCapitalLoanBreachSchedule lastPeriod = period(1, LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 10), 17);
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(lastPeriod));
        // The second lookup sees the period the split has generated by then, which already covers the business date.
        when(repository.findTopByLoanIdOrderByPeriodNumberDesc(LOAN_ID)).thenReturn(Optional.of(lastPeriod),
                Optional.of(period(2, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7), 7)));
        when(breachActionRepository.findByWorkingCapitalLoanIdOrderById(LOAN_ID)).thenReturn(List.of());
        when(transactionRepository.fetchTransactionDateAndAmount(anyLong(), any())).thenReturn(List.of());
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        underTest.splitPeriodAtReset(loan, LocalDate.of(2026, 6, 1));

        assertEquals(LocalDate.of(2026, 5, 25), lastPeriod.getFromDate());
        assertEquals(LocalDate.of(2026, 5, 31), lastPeriod.getToDate());
        assertEquals(7, lastPeriod.getNumberOfDays().intValue());

        final ArgumentCaptor<List<WorkingCapitalLoanBreachSchedule>> generated = ArgumentCaptor.captor();
        verify(repository).saveAllAndFlush(generated.capture());
        assertEquals(1, generated.getValue().size());
        final WorkingCapitalLoanBreachSchedule newPeriod = generated.getValue().getFirst();
        assertEquals(2, newPeriod.getPeriodNumber().intValue());
        assertEquals(LocalDate.of(2026, 6, 1), newPeriod.getFromDate());
        assertEquals(LocalDate.of(2026, 6, 7), newPeriod.getToDate());
        assertEquals(7, newPeriod.getNumberOfDays().intValue());
    }

    @Test
    void splitPeriodAtReset_resetDateStartingTheLastPeriod_leavesTheScheduleUntouched() {
        final WorkingCapitalLoanBreachSchedule lastPeriod = period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10), 10);
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(lastPeriod));

        underTest.splitPeriodAtReset(loan, LocalDate.of(2026, 5, 1));

        assertEquals(LocalDate.of(2026, 5, 10), lastPeriod.getToDate());
        assertEquals(10, lastPeriod.getNumberOfDays().intValue());
        verify(repository, never()).saveAllAndFlush(any());
        verify(transactionRepository, never()).fetchTransactionDateAndAmount(anyLong(), any());
    }

    @Test
    void splitPeriodAtReset_resetDateBeforeTheLastPeriod_leavesTheScheduleUntouched() {
        // Reachable when the business date is moved backwards after the schedule was generated past it: the reset date
        // then falls into an earlier period and must not truncate the last one to a negative length.
        final WorkingCapitalLoanBreachSchedule lastPeriod = period(2, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20), 10);
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(lastPeriod));

        underTest.splitPeriodAtReset(loan, LocalDate.of(2026, 5, 5));

        assertEquals(LocalDate.of(2026, 5, 11), lastPeriod.getFromDate());
        assertEquals(LocalDate.of(2026, 5, 20), lastPeriod.getToDate());
        assertEquals(10, lastPeriod.getNumberOfDays().intValue());
        verify(repository, never()).saveAllAndFlush(any());
        verify(transactionRepository, never()).fetchTransactionDateAndAmount(anyLong(), any());
    }

    @Test
    void restoreSplitPeriod_noPeriodEndingTheDayBeforeTheResetDate_leavesTheScheduleUntouched() {
        givenBreachConfig(7, WorkingCapitalLoanPeriodFrequencyType.DAYS);
        final WorkingCapitalLoanBreachSchedule period1 = period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10), 10);
        final WorkingCapitalLoanBreachSchedule period2 = period(2, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 20), 10);
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(period1, period2));

        assertFalse(underTest.restoreSplitPeriod(loan, LocalDate.of(2026, 5, 15)));

        assertEquals(LocalDate.of(2026, 5, 10), period1.getToDate());
        assertEquals(LocalDate.of(2026, 5, 20), period2.getToDate());
        verify(repository, never()).deleteAll(any());
        verify(repository, never()).flush();
        verify(transactionRepository, never()).fetchTransactionDateAndAmount(anyLong(), any());
    }

    @Test
    void restoreSplitPeriod_splitCreatedPeriodNoLongerPresent_leavesTheScheduleUntouched() {
        givenBreachConfig(7, WorkingCapitalLoanPeriodFrequencyType.DAYS);
        // The end date of period 1 still matches the reset date, but no period starts on it: the schedule was rewritten
        // after the reset, so the split this undo would revert is no longer there.
        final WorkingCapitalLoanBreachSchedule period1 = period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4), 4);
        final WorkingCapitalLoanBreachSchedule period2 = period(2, LocalDate.of(2026, 5, 6), LocalDate.of(2026, 5, 12), 7);
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(period1, period2));

        assertFalse(underTest.restoreSplitPeriod(loan, LocalDate.of(2026, 5, 5)));

        assertEquals(LocalDate.of(2026, 5, 1), period1.getFromDate());
        assertEquals(LocalDate.of(2026, 5, 4), period1.getToDate());
        assertEquals(4, period1.getNumberOfDays().intValue());
        verify(repository, never()).deleteAll(any());
        verify(repository, never()).flush();
    }

    @Test
    void restoreSplitPeriod_resetOnAPeriodStartDate_writesNothingOnThePeriod() {
        givenBreachConfig(7, WorkingCapitalLoanPeriodFrequencyType.DAYS);
        // Period 1 already ends where its frequency puts it, so the reset split nothing. The stale day count is the
        // marker: the no-op path must decide before writing anything on the period.
        final WorkingCapitalLoanBreachSchedule period1 = period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 7), 99);
        final WorkingCapitalLoanBreachSchedule period2 = period(2, LocalDate.of(2026, 5, 8), LocalDate.of(2026, 5, 14), 7);
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(period1, period2));
        when(breachActionRepository.findByWorkingCapitalLoanIdOrderById(LOAN_ID)).thenReturn(List.of());

        assertFalse(underTest.restoreSplitPeriod(loan, LocalDate.of(2026, 5, 8)));

        assertEquals(LocalDate.of(2026, 5, 1), period1.getFromDate());
        assertEquals(LocalDate.of(2026, 5, 7), period1.getToDate());
        assertEquals(99, period1.getNumberOfDays().intValue());
        verify(repository, never()).deleteAll(any());
        verify(repository, never()).flush();
        verify(transactionRepository, never()).fetchTransactionDateAndAmount(anyLong(), any());
    }

    @Test
    void restoreSplitPeriod_restoresTheTruncatedPeriodAndDeletesEverythingFromTheResetDate() {
        givenBreachConfig(7, WorkingCapitalLoanPeriodFrequencyType.DAYS);
        final WorkingCapitalLoanBreachSchedule truncatedPeriod = period(1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4), 4);
        final WorkingCapitalLoanBreachSchedule splitPeriod = period(2, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 11), 7);
        final WorkingCapitalLoanBreachSchedule nextPeriod = period(3, LocalDate.of(2026, 5, 12), LocalDate.of(2026, 6, 15), 35);
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(truncatedPeriod, splitPeriod, nextPeriod),
                List.of(truncatedPeriod));
        when(repository.findTopByLoanIdOrderByPeriodNumberDesc(LOAN_ID)).thenReturn(Optional.of(truncatedPeriod));
        when(breachActionRepository.findByWorkingCapitalLoanIdOrderById(LOAN_ID)).thenReturn(List.of());
        when(transactionRepository.fetchTransactionDateAndAmount(anyLong(), any())).thenReturn(List.of());
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        assertTrue(underTest.restoreSplitPeriod(loan, LocalDate.of(2026, 5, 5)));

        assertEquals(LocalDate.of(2026, 5, 1), truncatedPeriod.getFromDate());
        assertEquals(LocalDate.of(2026, 5, 7), truncatedPeriod.getToDate());
        assertEquals(7, truncatedPeriod.getNumberOfDays().intValue());

        final ArgumentCaptor<List<WorkingCapitalLoanBreachSchedule>> deleted = ArgumentCaptor.captor();
        verify(repository).deleteAll(deleted.capture());
        assertEquals(List.of(splitPeriod, nextPeriod), deleted.getValue());
        verify(repository).flush();
        // The restore repopulates the schedule itself, so the caller is not left with a truncated one.
        verify(transactionRepository).fetchTransactionDateAndAmount(anyLong(), any());
    }

    @Test
    void restoreSplitPeriod_restoresThePauseExtendedBoundaryOfTheTruncatedPeriod() {
        givenBreachConfig(60, WorkingCapitalLoanPeriodFrequencyType.DAYS);
        // Natural boundary 02 Mar + 59 days = 30 Apr, extended by the 10 day pause to 10 May: 70 days.
        final WorkingCapitalLoanBreachSchedule truncatedPeriod = period(2, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 4, 14), 44);
        final WorkingCapitalLoanBreachSchedule splitPeriod = period(3, LocalDate.of(2026, 4, 15), LocalDate.of(2026, 6, 13), 60);
        when(repository.findByLoanIdOrderByPeriodNumberAsc(LOAN_ID)).thenReturn(List.of(truncatedPeriod, splitPeriod),
                List.of(truncatedPeriod));
        when(repository.findTopByLoanIdOrderByPeriodNumberDesc(LOAN_ID)).thenReturn(Optional.of(truncatedPeriod));
        when(breachActionRepository.findByWorkingCapitalLoanIdOrderById(LOAN_ID))
                .thenReturn(List.of(pause(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 29))));
        when(transactionRepository.fetchTransactionDateAndAmount(anyLong(), any())).thenReturn(List.of());
        when(balanceRepository.findByWcLoan_Id(LOAN_ID)).thenReturn(Optional.of(balance));

        assertTrue(underTest.restoreSplitPeriod(loan, LocalDate.of(2026, 4, 15)));

        assertEquals(LocalDate.of(2026, 3, 2), truncatedPeriod.getFromDate());
        assertEquals(LocalDate.of(2026, 5, 10), truncatedPeriod.getToDate());
        assertEquals(70, truncatedPeriod.getNumberOfDays().intValue());
    }

    @Test
    void restoreSplitPeriod_noBreachConfiguration_leavesTheScheduleUntouched() {
        assertFalse(underTest.restoreSplitPeriod(loan, LocalDate.of(2026, 5, 5)));

        verify(repository, never()).findByLoanIdOrderByPeriodNumberAsc(anyLong());
        verify(repository, never()).deleteAll(any());
    }
}
