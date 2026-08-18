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

import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoan;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachAction;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachSchedule;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanBreachScheduleRepository;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Slf4j
@Service
public class WorkingCapitalLoanBreachResetServiceImpl implements WorkingCapitalLoanBreachResetService {

    private final WorkingCapitalLoanBreachScheduleRepository breachScheduleRepository;
    private final WorkingCapitalLoanBreachScheduleService breachScheduleService;
    private final WorkingCapitalLoanActiveBreachResetResolver activeBreachResetResolver;

    @Override
    public void resetBreach(final WorkingCapitalLoan loan, final WorkingCapitalLoanBreachAction resetAction) {
        final LocalDate actionDate = resetAction.getStartDate();
        if (actionDate == null) {
            return;
        }

        if (Boolean.TRUE.equals(resetAction.getRestartPeriodFromResetDate())) {
            breachScheduleService.splitPeriodAtReset(loan, actionDate);
        }

        breachScheduleRepository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(loan.getId(), actionDate, actionDate)
                .filter(period -> !period.isReset()).ifPresent(period -> {
                    period.setReset(true);
                });
        breachScheduleService.recalculatePastDueAmount(loan);
    }

    @Override
    public void undoResetBreach(final WorkingCapitalLoan loan, final WorkingCapitalLoanBreachAction undoResetAction) {
        if (undoResetAction.getStartDate() == null) {
            return;
        }
        // The reset this undo cancels is the latest one still active before the undo was recorded.
        final Optional<WorkingCapitalLoanBreachAction> undoneReset = activeBreachResetResolver.findResetUndoneBy(loan.getId(),
                undoResetAction);
        // The flag sits on the period the reset date fell into, which is not the period holding the undo date once the
        // schedule has moved on since the reset.
        final LocalDate flaggedDate = undoneReset.map(WorkingCapitalLoanBreachAction::getStartDate)
                .orElseGet(undoResetAction::getStartDate);
        breachScheduleRepository.findByLoanIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(loan.getId(), flaggedDate, flaggedDate)
                .filter(WorkingCapitalLoanBreachSchedule::isReset).or(() -> findLatestFlaggedPeriod(loan.getId(), flaggedDate))
                .ifPresent(period -> {
                    period.setReset(false);
                });

        if (!restoreSplitPeriodOfUndoneReset(loan, undoneReset)) {
            breachScheduleService.recalculatePastDueAmount(loan);
        }
    }

    /**
     * The flagged period when the reset date no longer points at the row carrying the flag. Period boundaries are
     * rewritten whenever the schedule is recalculated - a pause recorded after the reset does exactly that - while the
     * flag stays on its original row, so the reset date can fall into a different, unflagged period. Resets are undone
     * in LIFO order, the undone reset is always the latest active one, so the row to clear is the last flagged period.
     */
    private Optional<WorkingCapitalLoanBreachSchedule> findLatestFlaggedPeriod(final Long loanId, final LocalDate flaggedDate) {
        final Optional<WorkingCapitalLoanBreachSchedule> latestFlaggedPeriod = breachScheduleRepository
                .findTopByLoanIdAndResetTrueOrderByPeriodNumberDesc(loanId);
        latestFlaggedPeriod.ifPresent(period -> log.debug(
                "No period flagged as reset covers {} on working capital loan {}, the period boundaries were rewritten since the reset; clearing the flag on the latest flagged period {} instead",
                flaggedDate, loanId, period.getPeriodNumber()));
        return latestFlaggedPeriod;
    }

    /**
     * Reverts the period split of the reset this undo cancels. Only a reset performed with the restart period option
     * can have split a period, and even then only when its reset date fell inside a period. A reverted split leaves the
     * schedule consistent on its own, so nothing further is needed from here.
     */
    private boolean restoreSplitPeriodOfUndoneReset(final WorkingCapitalLoan loan,
            final Optional<WorkingCapitalLoanBreachAction> undoneReset) {
        return undoneReset.filter(reset -> Boolean.TRUE.equals(reset.getRestartPeriodFromResetDate()))
                .map(reset -> breachScheduleService.restoreSplitPeriod(loan, reset.getStartDate())).orElse(false);
    }
}
