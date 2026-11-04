<template>
  <el-card class="page-container">
    <el-alert
      v-if="recoveryWarning"
      :title="recoveryWarning"
      type="warning"
      :closable="false"
      show-icon
      data-cy="evaluation-recovery"
    />
    <el-button
      v-if="recoveryRequired"
      data-cy="evaluation-reload"
      :disabled="isLoading"
      @click="recoverOnly"
      >Retry recovery</el-button
    >
    <template #header>
      <div class="header">
        <span
          >Submit Peer Evaluations for week:
          <el-tag type="primary" style="font-size: 14px; font-weight: bold">{{
            previousWeekRange
          }}</el-tag></span
        >
      </div>
      <div style="margin-top: 10px">
        <span>
          The default score for each criterion is 10. You can change it to a value between 0 and 10.
        </span>
        <!-- Remind the user that "You have NOT submitted the evaluation" -->
        <el-tag v-if="isNewSubmission" type="danger" style="font-size: 14px; font-weight: bold"
          >You have NOT submitted the evaluation. Click the "Submit" button.</el-tag
        >
      </div>
    </template>
    <!-- Evaluation Table, adding height can fix the table header -->
    <el-table
      :data="peerEvalutions"
      style="width: 100%"
      stripe
      border
      height="600"
      v-loading="loading"
      scrollbar-always-on
    >
      <el-table-column
        label="Student Name"
        prop="evaluateeName"
        min-width="100"
        fixed
      ></el-table-column>
      <el-table-column v-for="criterion in criteria" :key="criterion.criterionId" min-width="150">
        <template #header>
          <el-tooltip effect="dark" :content="criterion.criterion" placement="top">
            <span>{{ criterion.description }}</span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <div style="display: flex; justify-content: center; align-items: center">
            <el-input-number
              v-model="
                row.ratings.find((r: Rating) => r.criterionId === criterion.criterionId).actualScore
              "
              :disabled="isLoading || recoveryRequired || !initializationComplete"
              :min="0"
              :max="criterion.maxScore"
              :step="1"
              controls-position="right"
              style="width: 80px"
            />
          </div>
        </template>
      </el-table-column>
      <el-table-column min-width="200px">
        <template #header>
          <el-tooltip
            effect="dark"
            content="Public comments are available to the evaluatee."
            placement="top"
          >
            <span>Public Comment</span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <el-input
            v-model="row.publicComment"
            :disabled="isLoading || recoveryRequired || !initializationComplete"
            type="textarea"
            :rows="5"
            placeholder="Enter public comment"
            max-length="255"
            show-word-limit
          />
        </template>
      </el-table-column>
      <el-table-column min-width="200px">
        <template #header>
          <el-tooltip
            effect="dark"
            content="Private comments are ONLY available to the instructor."
            placement="top"
          >
            <span>Private Comment</span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <el-input
            v-model="row.privateComment"
            :disabled="isLoading || recoveryRequired || !initializationComplete"
            type="textarea"
            :rows="5"
            placeholder="Enter private comment"
            max-length="255"
            show-word-limit
          />
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="No data is available." />
      </template>
    </el-table>
    <!-- submit button on the right side-->
    <div style="display: flex; justify-content: flex-end">
      <el-button
        data-cy="evaluation-submit"
        type="primary"
        @click="savePeerEvaluations"
        :loading="isLoading"
        :disabled="
          loading ||
          isLoading ||
          recoveryRequired ||
          !initializationComplete ||
          !peerEvalutions.length
        "
      >
        {{ isNewSubmission ? 'Submit' : 'Update' }}
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { isAxiosError } from 'axios'
import { findStudentsByTeamId } from '@/apis/student'
import type { Student } from '@/apis/student/types'
import { findSectionById } from '@/apis/section'
import { findRubricById } from '@/apis/rubric'
import type { Criterion } from '@/apis/rubric/types'
import { getPreviousWeek, getPreviousWeekRange } from '@/utils/week'
import { useUserInfoStore } from '@/stores/userInfo'
import {
  submitEvaluation,
  getEvaluationsByEvaluatorIdAndWeek,
  updateEvaluation,
  sendPeerEvaluationSubmissionConfirmationEmail
} from '@/apis/evaluation'
import { ElMessage } from 'element-plus'
import type { PeerEvaluation, Rating } from '@/apis/evaluation/types'

type Draft = PeerEvaluation & { outcome?: 'saved' | 'unsaved' | 'rejected' | 'unknown' }
const previousWeek = ref('')
const previousWeekRange = ref('')
const peerEvalutions = ref<Draft[]>([])
const loading = ref(false)
const isLoading = ref(false)
const recoveryRequired = ref(false)
const recoveryWarning = ref('')
const isNewSubmission = computed(() => peerEvalutions.value.some((row) => !row.evaluationId))
const criteria = ref<Criterion[]>([])
const initializationComplete = ref(false)
const userInfoStore = useUserInfoStore()
const student = computed(() => userInfoStore.userInfo as Student)
let confirmationOwed = false
const key = (row: PeerEvaluation) => `${row.evaluatorId}/${row.evaluateeId}/${row.week}`

function candidate(recipient: Student): Draft {
  return {
    week: previousWeek.value,
    evaluatorId: student.value.id,
    evaluateeId: recipient.id!,
    evaluateeName: `${recipient.firstName} ${recipient.lastName}`,
    ratings: criteria.value.map((criterion) => ({
      criterionId: criterion.criterionId!,
      criterion: criterion.criterion,
      actualScore: Math.min(10, criterion.maxScore)
    })),
    publicComment: '',
    privateComment: '',
    outcome: 'unsaved'
  }
}

// Retry the full section/rubric dependency chain unless it completed successfully.
async function ensureInitialized() {
  if (initializationComplete.value) return
  const section = await findSectionById(student.value.sectionId!)
  if (!section.flag || !section.data?.rubricId)
    throw new Error('The course section rubric is unavailable.')
  const rubric = await findRubricById(section.data.rubricId)
  if (!rubric.flag || !rubric.data?.criteria?.length)
    throw new Error('The evaluation rubric is unavailable.')
  criteria.value = rubric.data.criteria
  initializationComplete.value = true
}

// Reconcile by persisted ID and logical identity. Never discard drafts when either recovery read fails.
async function reconcile() {
  const [roster, stored] = await Promise.all([
    findStudentsByTeamId(student.value.teamId!),
    getEvaluationsByEvaluatorIdAndWeek(student.value.id!, previousWeek.value)
  ])
  const active = roster.data.filter((recipient) => recipient.enabled)
  const byId = new Map(stored.data.map((row) => [row.evaluationId, row]))
  const byIdentity = new Map(stored.data.map((row) => [key(row), row]))
  const merged: Draft[] = []
  let removed = 0
  let unresolved = false
  for (const draft of peerEvalutions.value) {
    const saved = (draft.evaluationId && byId.get(draft.evaluationId)) || byIdentity.get(key(draft))
    if (saved)
      merged.push({ ...saved, ...draft, evaluationId: saved.evaluationId, outcome: 'saved' })
    else if (draft.evaluationId || draft.outcome === 'unknown') {
      merged.push(draft)
      unresolved = true
    } else if (active.some((recipient) => recipient.id === draft.evaluateeId))
      merged.push({ ...draft, outcome: 'unsaved' })
    else removed++
  }
  for (const saved of stored.data) {
    if (!merged.some((row) => row.evaluationId === saved.evaluationId || key(row) === key(saved)))
      merged.push({ ...saved, outcome: 'saved' })
  }
  for (const recipient of active) {
    if (!merged.some((row) => row.evaluateeId === recipient.id)) merged.push(candidate(recipient))
  }
  peerEvalutions.value = merged
  recoveryRequired.value = unresolved
  const notices: string[] = []
  if (removed)
    notices.push(
      `${removed} unsaved evaluation(s) removed because the recipient is inactive. Active drafts and saved evaluations were retained.`
    )
  if (unresolved)
    notices.push(
      'Some submission outcomes are still unknown. Drafts are retained; retry recovery before submitting.'
    )
  else
    notices.push(
      'Current recipients and saved evaluations loaded. Review your retained drafts before retrying.'
    )
  recoveryWarning.value = notices.join(' ')
}

onMounted(async () => {
  previousWeek.value = getPreviousWeek()
  previousWeekRange.value = getPreviousWeekRange()
  if (!student.value.teamId) {
    ElMessage.error('You have not been assigned a team yet.')
    return
  }
  loading.value = true
  try {
    await ensureInitialized()
    await reconcile()
    recoveryWarning.value = ''
  } catch {
    recoveryRequired.value = true
    recoveryWarning.value = 'Evaluations could not be loaded. Retry recovery before submitting.'
  } finally {
    loading.value = false
  }
})

async function recoverOnly() {
  if (isLoading.value) return
  isLoading.value = true
  try {
    await ensureInitialized()
    await reconcile()
  } catch {
    recoveryWarning.value =
      'Recovery failed. Your drafts are retained. Retry recovery before submitting; unknown requests will not be replayed.'
    recoveryRequired.value = true
  } finally {
    isLoading.value = false
  }
}

async function savePeerEvaluations() {
  if (
    loading.value ||
    isLoading.value ||
    recoveryRequired.value ||
    !initializationComplete.value ||
    !peerEvalutions.value.length
  )
    return
  isLoading.value = true
  recoveryWarning.value = ''
  confirmationOwed ||= isNewSubmission.value
  try {
    const rows = peerEvalutions.value
    const outcomes = await Promise.allSettled(
      rows.map(async (row) => {
        const response = row.evaluationId
          ? await updateEvaluation(row)
          : await submitEvaluation(row)
        if (!response.flag) throw new Error(response.message)
        Object.assign(row, response.data, { outcome: 'saved' })
      })
    )
    if (outcomes.some((outcome) => outcome.status === 'rejected')) {
      outcomes.forEach((outcome, index) => {
        const row = rows[index]
        if (row && outcome.status === 'rejected') {
          // A validation/permission refusal establishes non-creation; timeouts, gateway and server
          // failures do not. In particular, HTTP 408 is not a definitive rejection.
          const status = isAxiosError(outcome.reason) ? outcome.reason.response?.status : undefined
          row.outcome =
            status !== undefined && [400, 401, 403, 404, 409, 422].includes(status)
              ? 'rejected'
              : 'unknown'
        }
      })
      recoveryRequired.value = true
      try {
        await reconcile()
      } catch {
        recoveryWarning.value =
          'Recovery failed. Your drafts are retained. Retry recovery before submitting; unknown requests will not be replayed.'
      }
      return
    }
    ElMessage.success('Peer evaluations saved successfully')
    if (confirmationOwed) {
      try {
        await sendPeerEvaluationSubmissionConfirmationEmail(previousWeek.value)
        confirmationOwed = false
        ElMessage.success('A confirmation email has been sent to you.')
      } catch {
        recoveryWarning.value =
          'Evaluations were saved, but the confirmation email could not be sent.'
      }
    }
  } finally {
    isLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.page-container {
  min-height: 100%;
  box-sizing: border-box;
  .header {
    display: flex;
    align-items: center;
    gap: 20px;
  }
}
</style>
