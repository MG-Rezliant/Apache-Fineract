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
package org.apache.fineract.cob.service;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.cob.COBConstant;
import org.apache.fineract.cob.data.COBIdAndLastClosedBusinessDate;
import org.apache.fineract.cob.domain.AccountLock;
import org.apache.fineract.cob.domain.AccountLockRepository;
import org.apache.fineract.cob.domain.LockOwner;
import org.apache.fineract.cob.exceptions.AccountLockCannotBeOverruledException;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.core.exception.PlatformRequestBodyItemLimitValidationException;
import org.apache.fineract.infrastructure.core.serialization.GoogleGsonSerializerHelper;
import org.apache.fineract.infrastructure.core.serialization.ThrowableSerialization;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.data.JobParameterDTO;
import org.apache.fineract.infrastructure.jobs.domain.CustomJobParameterRepository;
import org.apache.fineract.infrastructure.jobs.exception.JobNotFoundException;
import org.apache.fineract.infrastructure.jobs.service.InlineExecutorService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.springbatch.NextJobParametersResolver;
import org.apache.fineract.infrastructure.springbatch.SpringBatchJobConstants;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@RequiredArgsConstructor
public abstract class InlineCommonLockableCOBExecutorService<T extends AccountLock> implements InlineExecutorService<Long> {

    private static final String JOB_EXECUTION_FAILED_MESSAGE = "Job execution failed for job with name: ";
    private final AccountLockRepository<T> loanAccountLockRepository;
    private final InlineLoanCOBExecutionDataParser dataParser;
    private final JobOperator jobOperator;
    private final JobRegistry jobRegistry;
    private final JobRepository jobRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final CustomJobParameterRepository customJobParameterRepository;
    private final PlatformSecurityContext context;
    private final RetrieveIdService retrieveIdService;
    private final FineractProperties fineractProperties;

    private final Gson gson = GoogleGsonSerializerHelper.createSimpleGson();

    public abstract T createAccountLock(Long loanId, LockOwner loanInlineCobProcessing, LocalDate businessDate);

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommandProcessingResult executeInlineJob(JsonCommand command, String jobName) throws AccountLockCannotBeOverruledException {
        List<Long> loanIds = dataParser.parseExecution(command);
        validateLoanIdsListSize(loanIds);
        execute(loanIds, jobName);
        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .build();
    }

    @Override
    public void execute(List<Long> loanIds, String jobName) {
        LocalDate cobBusinessDate = ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.COB_DATE);
        List<COBIdAndLastClosedBusinessDate> loansToBeProcessed = getLoansToBeProcessed(loanIds, cobBusinessDate);
        LocalDate executingBusinessDate = getOldestCOBBusinessDate(loansToBeProcessed).plusDays(1);
        if (!loansToBeProcessed.isEmpty()) {
            while (!DateUtils.isAfter(executingBusinessDate, cobBusinessDate)) {
                execute(getLoanIdsToBeProcessed(loansToBeProcessed, executingBusinessDate), jobName, executingBusinessDate);
                executingBusinessDate = executingBusinessDate.plusDays(1);
            }
        }
    }

    private List<Long> getLoanIdsToBeProcessed(List<COBIdAndLastClosedBusinessDate> loansToBeProcessed, LocalDate executingBusinessDate) {
        List<Long> loanIdsToBeProcessed = new ArrayList<>();
        loansToBeProcessed.forEach(loan -> {
            if (loan.getLastClosedBusinessDate() != null) {
                if (DateUtils.isBefore(loan.getLastClosedBusinessDate(), executingBusinessDate)) {
                    loanIdsToBeProcessed.add(loan.getId());
                }
            } else {
                loanIdsToBeProcessed.add(loan.getId());
            }
        });
        return loanIdsToBeProcessed;
    }

    @SuppressFBWarnings("SLF4J_SIGN_ONLY_FORMAT")
    private void execute(List<Long> loanIds, String jobName, LocalDate businessDate) {
        HashMap<BusinessDateType, LocalDate> originalBusinessDates = new HashMap<>(ThreadLocalContextUtil.getBusinessDates());
        ActionContext originalActionContext = ThreadLocalContextUtil.getActionContext();
        Map<Long, AccountLockAttempt> lockAttempts = Collections.emptyMap();
        boolean markLocksOnFailure = true;
        try {
            lockAttempts = lockLoanAccounts(loanIds, businessDate);
            Job inlineLoanCOBJob = jobRegistry.getJob(jobName);
            if (inlineLoanCOBJob == null) {
                throw new JobNotFoundException(jobName);
            }
            JobParameters jobParameters = new JobParametersBuilder(NextJobParametersResolver.resolve(jobRepository, inlineLoanCOBJob))
                    .addJobParameters(new JobParameters(new HashSet<>(getJobParametersMap(loanIds, businessDate).values())))
                    .toJobParameters();
            JobExecution jobExecution;
            try {
                // No incrementer on the job (see NextJobParametersResolver), so start(..) respects these parameters.
                jobExecution = jobOperator.start(inlineLoanCOBJob, jobParameters);
            } catch (Exception e) {
                throw propagate(e, jobName);
            }
            if (!BatchStatus.COMPLETED.equals(jobExecution.getStatus())) {
                markLocksOnFailure = !jobExecution.getStatus().isRunning();
                throw propagate(resolveJobExecutionFailure(jobExecution, jobName), jobName);
            }
        } catch (RuntimeException | Error e) {
            if (markLocksOnFailure && !lockAttempts.isEmpty()) {
                markResidualLocksWithError(lockAttempts, jobName, e);
            }
            log.error("{}{}", JOB_EXECUTION_FAILED_MESSAGE, jobName, e);
            throw e;
        } finally {
            ThreadLocalContextUtil.setBusinessDates(originalBusinessDates);
            ThreadLocalContextUtil.setActionContext(originalActionContext);
        }
    }

    private Throwable resolveJobExecutionFailure(JobExecution jobExecution, String jobName) {
        List<Throwable> failures = jobExecution.getAllFailureExceptions();
        if (!failures.isEmpty()) {
            Class<? extends Throwable>[] retryExceptions = getRetryExceptions();
            if (retryExceptions != null) {
                Optional<Throwable> retryableFailure = failures.stream().filter(failure -> Arrays.stream(retryExceptions)
                        .anyMatch(retryException -> retryException.isAssignableFrom(failure.getClass()))).findFirst();
                if (retryableFailure.isPresent()) {
                    return retryableFailure.get();
                }
            }
            return failures.getFirst();
        }
        return new PlatformInternalServerException("error.msg.sheduler.job.execution.failed", JOB_EXECUTION_FAILED_MESSAGE, jobName);
    }

    private Class<? extends Throwable>[] getRetryExceptions() {
        FineractProperties.RetryProperties retryProperties = fineractProperties.getRetry();
        if (retryProperties == null || retryProperties.getInstances() == null
                || retryProperties.getInstances().getExecuteCommand() == null) {
            return null;
        }
        return retryProperties.getInstances().getExecuteCommand().getRetryExceptions();
    }

    private RuntimeException propagate(Throwable failure, String jobName) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new PlatformInternalServerException("error.msg.sheduler.job.execution.failed", JOB_EXECUTION_FAILED_MESSAGE, jobName,
                failure);
    }

    private void markResidualLocksWithError(Map<Long, AccountLockAttempt> lockAttempts, String jobName, Throwable failure) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                String stacktrace = ThrowableSerialization.serialize(failure);
                loanAccountLockRepository
                        .findAllByLoanIdInAndLockOwner(new ArrayList<>(lockAttempts.keySet()), LockOwner.LOAN_INLINE_COB_PROCESSING)
                        .stream().filter(lock -> StringUtils.isBlank(lock.getError()))
                        .filter(lock -> lockAttempts.get(lock.getLoanId()).matches(lock)).forEach(lock -> {
                            lock.setError("Inline COB execution failed for account (id: %d), job: %s".formatted(lock.getLoanId(), jobName),
                                    stacktrace);
                            loanAccountLockRepository.saveAndFlush(lock);
                        });
            });
        } catch (RuntimeException lockUpdateFailure) {
            log.error("Failed to record inline COB error on residual account locks for job: {}", jobName, lockUpdateFailure);
            if (lockUpdateFailure != failure) {
                failure.addSuppressed(lockUpdateFailure);
            }
        }
    }

    private LocalDate getOldestCOBBusinessDate(List<COBIdAndLastClosedBusinessDate> loans) {
        COBIdAndLastClosedBusinessDate oldestLoan = loans.stream().min(Comparator
                .comparing(COBIdAndLastClosedBusinessDate::getLastClosedBusinessDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        return oldestLoan != null && oldestLoan.getLastClosedBusinessDate() != null ? oldestLoan.getLastClosedBusinessDate()
                : ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.COB_DATE).minusDays(1);
    }

    private List<COBIdAndLastClosedBusinessDate> getLoansToBeProcessed(List<Long> loanIds, LocalDate cobBusinessDate) {
        List<COBIdAndLastClosedBusinessDate> loanIdAndLastClosedBusinessDates = new ArrayList<>();
        List<List<Long>> partitions = Lists.partition(loanIds, fineractProperties.getQuery().getInClauseParameterSizeLimit());
        partitions.forEach(partition -> loanIdAndLastClosedBusinessDates
                .addAll(retrieveIdService.retrieveLoanIdsBehindDateOrNull(cobBusinessDate, partition)));
        return loanIdAndLastClosedBusinessDates;
    }

    private List<T> getLoanAccountLocks(List<Long> loanIds, LocalDate businessDate) {
        List<T> loanAccountLocks = new ArrayList<>();
        List<Long> alreadyLockedLoanIds = new ArrayList<>();
        loanIds.forEach(loanId -> {
            Optional<T> loanLockOptional = loanAccountLockRepository.findById(loanId);
            if (loanLockOptional.isPresent()) {
                T loanAccountLock = loanLockOptional.get();
                if (isLockOverrulable(loanAccountLock)) {
                    loanAccountLocks.add(loanAccountLock);
                } else {
                    alreadyLockedLoanIds.add(loanId);
                }
            } else {
                loanAccountLocks.add(createAccountLock(loanId, LockOwner.LOAN_INLINE_COB_PROCESSING, businessDate));
            }
        });
        if (!alreadyLockedLoanIds.isEmpty()) {
            String message = "There is a hard lock on the loan account without any error, so it can't be overruled.";
            String loanIdsMessage = " Locked loan IDs: " + alreadyLockedLoanIds;
            throw new AccountLockCannotBeOverruledException(message + loanIdsMessage);
        }

        return loanAccountLocks;
    }

    private Long saveCustomJobParameter(String paramName, String paramValue) {
        JobParameterDTO paramDTO = new JobParameterDTO(paramName, paramValue);
        Set<JobParameterDTO> paramSet = Collections.singleton(paramDTO);
        return customJobParameterRepository.save(paramSet);
    }

    private Map<String, JobParameter<?>> getJobParametersMap(List<Long> loanIds, LocalDate businessDate) {
        String parameterJson = gson.toJson(loanIds);
        Long loanIdsJobParameterId = saveCustomJobParameter(COBConstant.INLINE_IDS_PARAMETER_NAME, parameterJson);
        Long businessDateJobParameterId = saveCustomJobParameter(COBConstant.BUSINESS_DATE_PARAMETER_NAME,
                businessDate.format(DateTimeFormatter.ISO_DATE));
        Map<String, JobParameter<?>> jobParameterMap = new HashMap<>();
        jobParameterMap.put(SpringBatchJobConstants.CUSTOM_JOB_PARAMETER_ID_KEY,
                new JobParameter<>(SpringBatchJobConstants.CUSTOM_JOB_PARAMETER_ID_KEY, loanIdsJobParameterId, Long.class));
        jobParameterMap.put(COBConstant.BUSINESS_DATE_PARAMETER_NAME,
                new JobParameter<>(COBConstant.BUSINESS_DATE_PARAMETER_NAME, businessDateJobParameterId, Long.class));
        return jobParameterMap;
    }

    private Map<Long, AccountLockAttempt> lockLoanAccounts(List<Long> loanIds, LocalDate businessDate) {
        Map<Long, AccountLockAttempt> lockAttempts = new HashMap<>();
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            List<T> loanAccountLocks = getLoanAccountLocks(loanIds, businessDate);
            loanAccountLocks.forEach(loanAccountLock -> {
                try {
                    loanAccountLock.setNewLockOwner(LockOwner.LOAN_INLINE_COB_PROCESSING);
                    T savedLock = loanAccountLockRepository.saveAndFlush(loanAccountLock);
                    lockAttempts.put(savedLock.getLoanId(), new AccountLockAttempt(savedLock.getVersion()));
                } catch (Exception e) {
                    log.error("Error updating lock on loan account. Locked loan ID: {}", loanAccountLock.getLoanId(), e);
                    throw new AccountLockCannotBeOverruledException(
                            "Error updating lock on loan account. Locked loan ID: %s".formatted(loanAccountLock.getLoanId()), e);
                }
            });
        });
        return lockAttempts;
    }

    private record AccountLockAttempt(Long version) {

        private boolean matches(AccountLock lock) {
            return Objects.equals(version, lock.getVersion());
        }
    }

    private boolean isLockOverrulable(T loanAccountLock) {
        if (isBypassUser()) {
            return true;
        } else {
            return StringUtils.isNotBlank(loanAccountLock.getError());
        }
    }

    private boolean isBypassUser() {
        return context.getAuthenticatedUserIfPresent().isBypassUser();
    }

    private void validateLoanIdsListSize(List<Long> loanIds) {
        int inlineLoanCobRequestItemLimit = fineractProperties.getApi().getBodyItemSizeLimit().getInlineLoanCob();
        if (loanIds.size() > inlineLoanCobRequestItemLimit) {
            String userMessage = "Size of the loan IDs list cannot be over " + inlineLoanCobRequestItemLimit;
            throw new PlatformRequestBodyItemLimitValidationException(userMessage);
        }
    }
}
