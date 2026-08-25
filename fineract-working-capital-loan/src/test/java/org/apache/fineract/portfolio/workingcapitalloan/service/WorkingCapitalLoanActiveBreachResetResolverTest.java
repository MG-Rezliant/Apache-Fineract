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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachAction;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachActionType;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanBreachActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The LIFO replay deciding which reset is active and which reset an undo cancels. The repository is the only
 * collaborator; the replay itself is exercised for real.
 */
@ExtendWith(MockitoExtension.class)
class WorkingCapitalLoanActiveBreachResetResolverTest {

    private static final Long LOAN_ID = 1L;

    @Mock
    private WorkingCapitalLoanBreachActionRepository breachActionRepository;

    private WorkingCapitalLoanActiveBreachResetResolver underTest;

    @BeforeEach
    void setUp() {
        underTest = new WorkingCapitalLoanActiveBreachResetResolver(breachActionRepository);
    }

    private WorkingCapitalLoanBreachAction action(final long id, final WorkingCapitalLoanBreachActionType type, final LocalDate startDate) {
        final WorkingCapitalLoanBreachAction action = new WorkingCapitalLoanBreachAction();
        action.setId(id);
        action.setAction(type);
        action.setStartDate(startDate);
        return action;
    }

    private void givenResetActions(final List<WorkingCapitalLoanBreachAction> actions) {
        when(breachActionRepository.findByLoanAndActionType(eq(LOAN_ID), any())).thenReturn(actions);
    }

    @Test
    void findLatestActiveReset_returnsTheOnlyReset() {
        final WorkingCapitalLoanBreachAction reset = action(1L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 4, 15));
        givenResetActions(List.of(reset));

        final Optional<WorkingCapitalLoanBreachAction> actual = underTest.findLatestActiveReset(LOAN_ID);

        assertTrue(actual.isPresent());
        assertEquals(1L, actual.get().getId().longValue());
        assertTrue(underTest.hasActiveReset(LOAN_ID));
    }

    @Test
    void findLatestActiveReset_returnsEmptyWhenTheOnlyResetWasUndone() {
        givenResetActions(List.of(action(1L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 4, 15)),
                action(2L, WorkingCapitalLoanBreachActionType.UNDO_RESET, LocalDate.of(2026, 4, 20))));

        assertEquals(Optional.empty(), underTest.findLatestActiveReset(LOAN_ID));
        assertFalse(underTest.hasActiveReset(LOAN_ID));
    }

    @Test
    void findLatestActiveReset_returnsLatestOfStackedResets() {
        givenResetActions(List.of(action(1L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 4, 15)),
                action(2L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 5, 20))));

        final Optional<WorkingCapitalLoanBreachAction> actual = underTest.findLatestActiveReset(LOAN_ID);

        assertTrue(actual.isPresent());
        assertEquals(2L, actual.get().getId().longValue());
        assertEquals(LocalDate.of(2026, 5, 20), actual.get().getStartDate());
    }

    @Test
    void findLatestActiveReset_undoOfTheLatestStackedResetLeavesTheEarlierOneActive() {
        givenResetActions(List.of(action(1L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 4, 15)),
                action(2L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 5, 20)),
                action(3L, WorkingCapitalLoanBreachActionType.UNDO_RESET, LocalDate.of(2026, 5, 25))));

        final Optional<WorkingCapitalLoanBreachAction> actual = underTest.findLatestActiveReset(LOAN_ID);

        assertTrue(actual.isPresent());
        assertEquals(1L, actual.get().getId().longValue());
        assertEquals(LocalDate.of(2026, 4, 15), actual.get().getStartDate());
    }

    @Test
    void findResetUndoneBy_returnsTheResetPrecedingTheUndoWithoutReplayingTheUndoItself() {
        final WorkingCapitalLoanBreachAction undo = action(2L, WorkingCapitalLoanBreachActionType.UNDO_RESET, LocalDate.of(2026, 4, 20));
        // The undo row is already flushed when the undo is processed, so it comes back from the query as well.
        givenResetActions(List.of(action(1L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 4, 15)), undo));

        final Optional<WorkingCapitalLoanBreachAction> actual = underTest.findResetUndoneBy(LOAN_ID, undo);

        assertTrue(actual.isPresent());
        assertEquals(1L, actual.get().getId().longValue());
        assertEquals(LocalDate.of(2026, 4, 15), actual.get().getStartDate());
        // The same replay including the undo row finds nothing left active - that is why the undo needs its own lookup.
        assertEquals(Optional.empty(), underTest.findLatestActiveReset(LOAN_ID));
    }

    @Test
    void findResetUndoneBy_returnsTheLatestOfStackedResets() {
        final WorkingCapitalLoanBreachAction undo = action(3L, WorkingCapitalLoanBreachActionType.UNDO_RESET, LocalDate.of(2026, 5, 25));
        givenResetActions(List.of(action(1L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 4, 15)),
                action(2L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 5, 20)), undo));

        final Optional<WorkingCapitalLoanBreachAction> actual = underTest.findResetUndoneBy(LOAN_ID, undo);

        assertTrue(actual.isPresent());
        assertEquals(2L, actual.get().getId().longValue());
        assertEquals(LocalDate.of(2026, 5, 20), actual.get().getStartDate());
    }

    @Test
    void findResetUndoneBy_secondUndoOfStackedResetsCancelsTheEarlierReset() {
        final WorkingCapitalLoanBreachAction secondUndo = action(4L, WorkingCapitalLoanBreachActionType.UNDO_RESET,
                LocalDate.of(2026, 5, 30));
        givenResetActions(List.of(action(1L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 4, 15)),
                action(2L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 5, 20)),
                action(3L, WorkingCapitalLoanBreachActionType.UNDO_RESET, LocalDate.of(2026, 5, 25)), secondUndo));

        final Optional<WorkingCapitalLoanBreachAction> actual = underTest.findResetUndoneBy(LOAN_ID, secondUndo);

        assertTrue(actual.isPresent());
        assertEquals(1L, actual.get().getId().longValue());
        assertEquals(LocalDate.of(2026, 4, 15), actual.get().getStartDate());
    }

    @Test
    void findResetUndoneBy_backwardsDatedLaterResetIsStillCancelledByItsOwnUndo() {
        // Action dates are the business date of their own request, so a backwards move of the business date makes them
        // non-monotonic. The replay follows creation order, which is what the repository query orders by.
        final WorkingCapitalLoanBreachAction undo = action(3L, WorkingCapitalLoanBreachActionType.UNDO_RESET, LocalDate.of(2026, 4, 10));
        givenResetActions(List.of(action(1L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 5, 20)),
                action(2L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 4, 5)), undo));

        final Optional<WorkingCapitalLoanBreachAction> actual = underTest.findResetUndoneBy(LOAN_ID, undo);

        assertTrue(actual.isPresent());
        assertEquals(2L, actual.get().getId().longValue());
        assertEquals(LocalDate.of(2026, 4, 5), actual.get().getStartDate());
    }

    @Test
    void findResetUndoneBy_returnsEmptyWhenNoResetPrecedesTheUndo() {
        final WorkingCapitalLoanBreachAction undo = action(1L, WorkingCapitalLoanBreachActionType.UNDO_RESET, LocalDate.of(2026, 4, 20));
        givenResetActions(List.of(undo));

        assertEquals(Optional.empty(), underTest.findResetUndoneBy(LOAN_ID, undo));
    }

    @Test
    void existsActiveResetInPeriod_matchesOnlyTheRangeHoldingTheActiveResetDate() {
        givenResetActions(List.of(action(1L, WorkingCapitalLoanBreachActionType.RESET, LocalDate.of(2026, 4, 15))));

        assertTrue(underTest.existsActiveResetInPeriod(LOAN_ID, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));
        assertTrue(underTest.existsActiveResetInPeriod(LOAN_ID, LocalDate.of(2026, 4, 15), LocalDate.of(2026, 4, 15)));
        assertFalse(underTest.existsActiveResetInPeriod(LOAN_ID, LocalDate.of(2026, 4, 16), LocalDate.of(2026, 4, 30)));
    }
}
