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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoan;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachAction;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachActionType;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachSchedule;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanBreachScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkingCapitalLoanBreachResetServiceImplTest {

    private static final Long LOAN_ID = 1L;

    @Mock
    private WorkingCapitalLoanBreachScheduleRepository breachScheduleRepository;

    @Mock
    private WorkingCapitalLoanBreachScheduleService breachScheduleService;

    @Mock
    private WorkingCapitalLoanActiveBreachResetResolver activeBreachResetResolver;

    private WorkingCapitalLoanBreachResetServiceImpl underTest;

    private WorkingCapitalLoan loan;

    @BeforeEach
    void setUp() {
        underTest = new WorkingCapitalLoanBreachResetServiceImpl(breachScheduleRepository, breachScheduleService,
                activeBreachResetResolver);
        loan = new WorkingCapitalLoan();
        loan.setId(LOAN_ID);
    }

    @Test
    void resetBreach_setsResetFlagOnActionDatePeriodAndKeepsValues() {
        final WorkingCapitalLoanBreachAction resetAction = new WorkingCapitalLoanBreachAction();
        resetAction.setAction(WorkingCapitalLoanBreachActionType.RESET);
        resetAction.setStartDate(LocalDate.of(2026, 4, 15));

        final WorkingCapitalLoanBreachSchedule pastPeriod = new WorkingCapitalLoanBreachSchedule();
        pastPeriod.setLoan(loan);
        pastPeriod.setPeriodNumber(1);
        pastPeriod.setFromDate(LocalDate.of(2026, 1, 1));
        pastPeriod.setToDate(LocalDate.of(2026, 3, 1));

        final WorkingCapitalLoanBreachSchedule actionDatePeriod = new WorkingCapitalLoanBreachSchedule();
        actionDatePeriod.setLoan(loan);
        actionDatePeriod.setPeriodNumber(2);
        actionDatePeriod.setFromDate(LocalDate.of(2026, 4, 1));
        actionDatePeriod.setToDate(LocalDate.of(2026, 4, 30));
        actionDatePeriod.setMinPaymentAmount(BigDecimal.valueOf(100));
        actionDatePeriod.setPaidAmount(BigDecimal.valueOf(40));
        actionDatePeriod.setOutstandingAmount(BigDecimal.valueOf(60));
        actionDatePeriod.setBreach(Boolean.TRUE);
        actionDatePeriod.setNearBreach(Boolean.FALSE);

        final WorkingCapitalLoanBreachSchedule futurePeriod = new WorkingCapitalLoanBreachSchedule();
        futurePeriod.setLoan(loan);
        futurePeriod.setPeriodNumber(3);
        futurePeriod.setFromDate(LocalDate.of(2026, 5, 1));
        futurePeriod.setToDate(LocalDate.of(2026, 5, 31));

        when(breachScheduleRepository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, resetAction.getStartDate(),
                resetAction.getStartDate())).thenReturn(Optional.of(actionDatePeriod));

        underTest.resetBreach(loan, resetAction);

        assertFalse(pastPeriod.isReset());
        assertTrue(actionDatePeriod.isReset());
        assertFalse(futurePeriod.isReset());
        assertEquals(0, BigDecimal.valueOf(100).compareTo(actionDatePeriod.getMinPaymentAmount()));
        assertEquals(0, BigDecimal.valueOf(40).compareTo(actionDatePeriod.getPaidAmount()));
        assertEquals(0, BigDecimal.valueOf(60).compareTo(actionDatePeriod.getOutstandingAmount()));
        assertEquals(Boolean.TRUE, actionDatePeriod.getBreach());
        assertEquals(Boolean.FALSE, actionDatePeriod.getNearBreach());
        verify(breachScheduleService).recalculatePastDueAmount(loan);
    }

    @Test
    void undoResetBreach_liftsResetFlagOnActionDatePeriodOnly() {
        final WorkingCapitalLoanBreachAction undoResetAction = new WorkingCapitalLoanBreachAction();
        undoResetAction.setAction(WorkingCapitalLoanBreachActionType.UNDO_RESET);
        undoResetAction.setStartDate(LocalDate.of(2026, 4, 15));

        final WorkingCapitalLoanBreachSchedule pastPeriod = new WorkingCapitalLoanBreachSchedule();
        pastPeriod.setLoan(loan);
        pastPeriod.setPeriodNumber(1);
        pastPeriod.setFromDate(LocalDate.of(2026, 1, 1));
        pastPeriod.setToDate(LocalDate.of(2026, 3, 1));
        pastPeriod.setReset(true);

        final WorkingCapitalLoanBreachSchedule actionDatePeriod = new WorkingCapitalLoanBreachSchedule();
        actionDatePeriod.setLoan(loan);
        actionDatePeriod.setPeriodNumber(2);
        actionDatePeriod.setFromDate(LocalDate.of(2026, 4, 1));
        actionDatePeriod.setToDate(LocalDate.of(2026, 4, 30));
        actionDatePeriod.setMinPaymentAmount(BigDecimal.valueOf(100));
        actionDatePeriod.setPaidAmount(BigDecimal.valueOf(40));
        actionDatePeriod.setOutstandingAmount(BigDecimal.valueOf(60));
        actionDatePeriod.setBreach(Boolean.TRUE);
        actionDatePeriod.setNearBreach(Boolean.FALSE);
        actionDatePeriod.setReset(true);

        final WorkingCapitalLoanBreachSchedule futurePeriod = new WorkingCapitalLoanBreachSchedule();
        futurePeriod.setLoan(loan);
        futurePeriod.setPeriodNumber(3);
        futurePeriod.setFromDate(LocalDate.of(2026, 5, 1));
        futurePeriod.setToDate(LocalDate.of(2026, 5, 31));
        futurePeriod.setReset(true);

        when(breachScheduleRepository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, undoResetAction.getStartDate(),
                undoResetAction.getStartDate())).thenReturn(Optional.of(actionDatePeriod));

        underTest.undoResetBreach(loan, undoResetAction);

        assertTrue(pastPeriod.isReset());
        assertFalse(actionDatePeriod.isReset());
        assertTrue(futurePeriod.isReset());
        assertEquals(0, BigDecimal.valueOf(100).compareTo(actionDatePeriod.getMinPaymentAmount()));
        assertEquals(0, BigDecimal.valueOf(40).compareTo(actionDatePeriod.getPaidAmount()));
        assertEquals(0, BigDecimal.valueOf(60).compareTo(actionDatePeriod.getOutstandingAmount()));
        assertEquals(Boolean.TRUE, actionDatePeriod.getBreach());
        assertEquals(Boolean.FALSE, actionDatePeriod.getNearBreach());
        verify(breachScheduleService).recalculatePastDueAmount(loan);
        verify(breachScheduleService, never()).reprocessBreachSchedule(loan);
    }

    @Test
    void resetBreach_withRestartPeriodOption_splitsThePeriodAtTheResetDate() {
        final WorkingCapitalLoanBreachAction resetAction = new WorkingCapitalLoanBreachAction();
        resetAction.setAction(WorkingCapitalLoanBreachActionType.RESET);
        resetAction.setStartDate(LocalDate.of(2026, 4, 15));
        resetAction.setRestartPeriodFromResetDate(true);

        underTest.resetBreach(loan, resetAction);

        verify(breachScheduleService).splitPeriodAtReset(loan, LocalDate.of(2026, 4, 15));
        verify(breachScheduleService).recalculatePastDueAmount(loan);
    }

    @Test
    void resetBreach_withoutRestartPeriodOption_leavesThePeriodsAsTheyAre() {
        final WorkingCapitalLoanBreachAction resetAction = new WorkingCapitalLoanBreachAction();
        resetAction.setAction(WorkingCapitalLoanBreachActionType.RESET);
        resetAction.setStartDate(LocalDate.of(2026, 4, 15));

        underTest.resetBreach(loan, resetAction);

        verify(breachScheduleService, never()).splitPeriodAtReset(any(), any());
    }

    @Test
    void undoResetBreach_liftsTheResetFlagOnThePeriodOfTheUndoneResetNotOfTheUndo() {
        final LocalDate resetDate = LocalDate.of(2026, 4, 15);
        final LocalDate undoDate = LocalDate.of(2026, 5, 20);

        final WorkingCapitalLoanBreachAction undoneReset = new WorkingCapitalLoanBreachAction();
        undoneReset.setAction(WorkingCapitalLoanBreachActionType.RESET);
        undoneReset.setStartDate(resetDate);
        // A restart reset that fell on a period start date splits nothing, so the undo has no split to restore - but
        // the flag it left behind still has to be lifted.
        undoneReset.setRestartPeriodFromResetDate(true);

        final WorkingCapitalLoanBreachAction undoResetAction = new WorkingCapitalLoanBreachAction();
        undoResetAction.setAction(WorkingCapitalLoanBreachActionType.UNDO_RESET);
        undoResetAction.setStartDate(undoDate);

        final WorkingCapitalLoanBreachSchedule resetDatePeriod = new WorkingCapitalLoanBreachSchedule();
        resetDatePeriod.setLoan(loan);
        resetDatePeriod.setPeriodNumber(2);
        resetDatePeriod.setFromDate(LocalDate.of(2026, 4, 15));
        resetDatePeriod.setToDate(LocalDate.of(2026, 4, 30));
        resetDatePeriod.setReset(true);

        final WorkingCapitalLoanBreachSchedule undoDatePeriod = new WorkingCapitalLoanBreachSchedule();
        undoDatePeriod.setLoan(loan);
        undoDatePeriod.setPeriodNumber(3);
        undoDatePeriod.setFromDate(LocalDate.of(2026, 5, 1));
        undoDatePeriod.setToDate(LocalDate.of(2026, 5, 31));
        undoDatePeriod.setReset(true);

        when(activeBreachResetResolver.findResetUndoneBy(LOAN_ID, undoResetAction)).thenReturn(Optional.of(undoneReset));
        when(breachScheduleRepository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, resetDate, resetDate))
                .thenReturn(Optional.of(resetDatePeriod));
        when(breachScheduleService.restoreSplitPeriod(loan, resetDate)).thenReturn(false);

        underTest.undoResetBreach(loan, undoResetAction);

        assertFalse(resetDatePeriod.isReset());
        assertTrue(undoDatePeriod.isReset());
        verify(breachScheduleRepository, never()).findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, undoDate,
                undoDate);
        verify(breachScheduleService).recalculatePastDueAmount(loan);
    }

    @Test
    void undoResetBreach_periodStillCoveringTheResetDate_clearsItWithoutLookingUpTheLatestFlaggedPeriod() {
        final LocalDate resetDate = LocalDate.of(2026, 4, 15);

        final WorkingCapitalLoanBreachAction undoneReset = new WorkingCapitalLoanBreachAction();
        undoneReset.setAction(WorkingCapitalLoanBreachActionType.RESET);
        undoneReset.setStartDate(resetDate);

        final WorkingCapitalLoanBreachAction undoResetAction = new WorkingCapitalLoanBreachAction();
        undoResetAction.setAction(WorkingCapitalLoanBreachActionType.UNDO_RESET);
        undoResetAction.setStartDate(LocalDate.of(2026, 5, 20));

        final WorkingCapitalLoanBreachSchedule resetDatePeriod = new WorkingCapitalLoanBreachSchedule();
        resetDatePeriod.setLoan(loan);
        resetDatePeriod.setPeriodNumber(2);
        resetDatePeriod.setFromDate(LocalDate.of(2026, 4, 1));
        resetDatePeriod.setToDate(LocalDate.of(2026, 4, 30));
        resetDatePeriod.setReset(true);

        when(activeBreachResetResolver.findResetUndoneBy(LOAN_ID, undoResetAction)).thenReturn(Optional.of(undoneReset));
        when(breachScheduleRepository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, resetDate, resetDate))
                .thenReturn(Optional.of(resetDatePeriod));

        underTest.undoResetBreach(loan, undoResetAction);

        assertFalse(resetDatePeriod.isReset());
        verify(breachScheduleRepository, never()).findTopByLoanIdAndResetTrueOrderByPeriodNumberDesc(LOAN_ID);
        verify(breachScheduleService).recalculatePastDueAmount(loan);
    }

    @Test
    void undoResetBreach_pauseMovedTheBoundariesSinceTheReset_clearsTheLatestFlaggedPeriod() {
        final LocalDate resetDate = LocalDate.of(2026, 4, 15);

        final WorkingCapitalLoanBreachAction undoneReset = new WorkingCapitalLoanBreachAction();
        undoneReset.setAction(WorkingCapitalLoanBreachActionType.RESET);
        undoneReset.setStartDate(resetDate);

        final WorkingCapitalLoanBreachAction undoResetAction = new WorkingCapitalLoanBreachAction();
        undoResetAction.setAction(WorkingCapitalLoanBreachActionType.UNDO_RESET);
        undoResetAction.setStartDate(LocalDate.of(2026, 6, 10));

        // The pause shifted the boundaries, so the reset date now falls into a period that never carried the flag.
        final WorkingCapitalLoanBreachSchedule resetDateCoveringPeriod = new WorkingCapitalLoanBreachSchedule();
        resetDateCoveringPeriod.setLoan(loan);
        resetDateCoveringPeriod.setPeriodNumber(2);
        resetDateCoveringPeriod.setFromDate(LocalDate.of(2026, 4, 1));
        resetDateCoveringPeriod.setToDate(LocalDate.of(2026, 4, 30));
        resetDateCoveringPeriod.setReset(false);

        final WorkingCapitalLoanBreachSchedule flaggedPeriod = new WorkingCapitalLoanBreachSchedule();
        flaggedPeriod.setLoan(loan);
        flaggedPeriod.setPeriodNumber(3);
        flaggedPeriod.setFromDate(LocalDate.of(2026, 5, 1));
        flaggedPeriod.setToDate(LocalDate.of(2026, 5, 31));
        flaggedPeriod.setReset(true);

        when(activeBreachResetResolver.findResetUndoneBy(LOAN_ID, undoResetAction)).thenReturn(Optional.of(undoneReset));
        when(breachScheduleRepository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, resetDate, resetDate))
                .thenReturn(Optional.of(resetDateCoveringPeriod));
        when(breachScheduleRepository.findTopByLoanIdAndResetTrueOrderByPeriodNumberDesc(LOAN_ID)).thenReturn(Optional.of(flaggedPeriod));

        underTest.undoResetBreach(loan, undoResetAction);

        assertFalse(flaggedPeriod.isReset());
        assertFalse(resetDateCoveringPeriod.isReset());
        verify(breachScheduleService).recalculatePastDueAmount(loan);
    }

    @Test
    void undoResetBreach_stackedResetsAfterTheBoundariesMoved_clearsOnlyTheLatestFlaggedPeriod() {
        final LocalDate laterResetDate = LocalDate.of(2026, 5, 10);

        final WorkingCapitalLoanBreachAction undoneReset = new WorkingCapitalLoanBreachAction();
        undoneReset.setAction(WorkingCapitalLoanBreachActionType.RESET);
        undoneReset.setStartDate(laterResetDate);

        final WorkingCapitalLoanBreachAction undoResetAction = new WorkingCapitalLoanBreachAction();
        undoResetAction.setAction(WorkingCapitalLoanBreachActionType.UNDO_RESET);
        undoResetAction.setStartDate(LocalDate.of(2026, 6, 10));

        final WorkingCapitalLoanBreachSchedule earlierFlaggedPeriod = new WorkingCapitalLoanBreachSchedule();
        earlierFlaggedPeriod.setLoan(loan);
        earlierFlaggedPeriod.setPeriodNumber(2);
        earlierFlaggedPeriod.setFromDate(LocalDate.of(2026, 4, 1));
        earlierFlaggedPeriod.setToDate(LocalDate.of(2026, 4, 30));
        earlierFlaggedPeriod.setReset(true);

        final WorkingCapitalLoanBreachSchedule latestFlaggedPeriod = new WorkingCapitalLoanBreachSchedule();
        latestFlaggedPeriod.setLoan(loan);
        latestFlaggedPeriod.setPeriodNumber(3);
        latestFlaggedPeriod.setFromDate(LocalDate.of(2026, 5, 20));
        latestFlaggedPeriod.setToDate(LocalDate.of(2026, 6, 20));
        latestFlaggedPeriod.setReset(true);

        when(activeBreachResetResolver.findResetUndoneBy(LOAN_ID, undoResetAction)).thenReturn(Optional.of(undoneReset));
        // The pause left a gap, no period covers the later reset date any more.
        when(breachScheduleRepository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(LOAN_ID, laterResetDate,
                laterResetDate)).thenReturn(Optional.empty());
        when(breachScheduleRepository.findTopByLoanIdAndResetTrueOrderByPeriodNumberDesc(LOAN_ID))
                .thenReturn(Optional.of(latestFlaggedPeriod));

        underTest.undoResetBreach(loan, undoResetAction);

        assertFalse(latestFlaggedPeriod.isReset());
        assertTrue(earlierFlaggedPeriod.isReset());
        verify(breachScheduleService).recalculatePastDueAmount(loan);
    }

    @Test
    void undoResetBreach_restoredSplitLeavesThePastDueRecalculationToTheRestore() {
        final LocalDate resetDate = LocalDate.of(2026, 4, 15);

        final WorkingCapitalLoanBreachAction undoneReset = new WorkingCapitalLoanBreachAction();
        undoneReset.setAction(WorkingCapitalLoanBreachActionType.RESET);
        undoneReset.setStartDate(resetDate);
        undoneReset.setRestartPeriodFromResetDate(true);

        final WorkingCapitalLoanBreachAction undoResetAction = new WorkingCapitalLoanBreachAction();
        undoResetAction.setAction(WorkingCapitalLoanBreachActionType.UNDO_RESET);
        undoResetAction.setStartDate(LocalDate.of(2026, 5, 20));

        when(activeBreachResetResolver.findResetUndoneBy(LOAN_ID, undoResetAction)).thenReturn(Optional.of(undoneReset));
        when(breachScheduleService.restoreSplitPeriod(loan, resetDate)).thenReturn(true);

        underTest.undoResetBreach(loan, undoResetAction);

        verify(breachScheduleService).restoreSplitPeriod(loan, resetDate);
        verify(breachScheduleService, never()).recalculatePastDueAmount(loan);
    }
}
