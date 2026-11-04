<template>
  <el-card class="page-container">
    <template #header>
      <div class="header">
        <span>Student Management</span>
      </div>
    </template>
    <!-- Search Form -->
    <el-form inline>
      <el-form-item label="Student First Name:">
        <el-input v-model="searchCriteria.firstName" @input="loadStudents" />
      </el-form-item>
      <el-form-item label="Student Last Name:">
        <el-input v-model="searchCriteria.lastName" @input="loadStudents" />
      </el-form-item>
      <el-form-item>
        <!-- <el-button type="primary" @click="loadStudents">Query</el-button> -->
        <el-button @click="resetSearchCriteria">Reset</el-button>
      </el-form-item>
    </el-form>
    <!-- Student Table -->
    <el-table
      :data="students"
      style="width: 100%"
      stripe
      border
      v-loading="loading"
      @row-click="goToStudentPerformanceDashboard"
      height="600"
    >
      <el-table-column label="Id" prop="id"></el-table-column>
      <el-table-column label="First Name" prop="firstName"></el-table-column>
      <el-table-column label="Last Name" prop="lastName"></el-table-column>
      <el-table-column label="Email" prop="email"></el-table-column>
      <el-table-column label="Team" prop="teamName"></el-table-column>
      <el-table-column label="Status" prop="enabled">
        <template #default="{ row }">
          <el-tag type="success" v-if="row.enabled">Active</el-tag>
          <el-tag type="danger" v-else>Inactive</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Operations" width="230">
        <template #default="{ row }">
          <el-button
            icon="Edit"
            aria-label="Edit student"
            circle
            plain
            type="primary"
            @click.stop="showEditStudentDialog(row)"
          ></el-button>
          <el-button
            v-if="userInfo.isInstructor || userInfo.isAdmin"
            data-cy="deactivate-open"
            type="danger"
            plain
            @click.stop="openDeactivation(row)"
            >Deactivate</el-button
          >
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="No data is available." />
      </template>
    </el-table>
    <!-- Pagination -->
    <el-pagination
      v-model:current-page="pageNumber"
      v-model:page-size="pageSize"
      :page-sizes="[10, 50, 100]"
      layout="jumper, total, sizes, prev, pager, next"
      background
      :total="totalElements"
      @size-change="handlePageSizeChange"
      @current-change="handlePageNumberChange"
      style="margin-top: 20px; justify-content: flex-end"
    />
    <!-- Dialog for editing student -->
    <el-dialog v-model="dialogVisible" title="Edit a student" width="30%" destroy-on-close>
      <EditStudentForm
        :studentId="studentBeingEditedId"
        @close-dialog="closeDialog"
      ></EditStudentForm>
    </el-dialog>
    <el-alert
      v-if="refreshWarning"
      :title="refreshWarning"
      type="warning"
      show-icon
      :closable="false"
      data-cy="student-refresh-warning"
    />
    <el-dialog
      v-model="deactivationVisible"
      title="Deactivate student"
      width="520px"
      data-cy="deactivate-dialog"
      :show-close="!deactivating"
      :close-on-click-modal="!deactivating"
      :close-on-press-escape="!deactivating"
    >
      <template v-if="deactivationTarget">
        <p>
          <strong>{{ deactivationTarget.firstName }} {{ deactivationTarget.lastName }}</strong> ({{
            deactivationTarget.email
          }})
        </p>
        <p>
          Course section: {{ deactivationTarget.sectionName }} · Team:
          {{ deactivationTarget.teamName || 'No team' }} · Status:
          {{ deactivationTarget.enabled ? 'Active' : 'Inactive' }}
        </p>
        <p>
          This student will lose access to Project Pulse, including submitting weekly activity
          reports and peer evaluations. New peer evaluations cannot target this student.
        </p>
        <p>
          Course enrollment, team membership, submitted work, existing evaluations and historical
          scores will be retained. Access can be restored later.
        </p>
        <el-alert
          v-if="deactivationError"
          :title="deactivationError"
          type="error"
          :closable="false"
          data-cy="deactivate-error"
        />
      </template>
      <template #footer>
        <el-button
          data-cy="deactivate-cancel"
          :disabled="deactivating"
          @click="deactivationVisible = false"
          >Cancel</el-button
        >
        <el-button v-if="deactivationError" :disabled="deactivating" @click="reloadDeactivation"
          >Reload current state</el-button
        >
        <el-button
          data-cy="deactivate-confirm"
          type="danger"
          :loading="deactivating"
          :disabled="deactivating"
          @click="confirmDeactivation"
          >Deactivate student</el-button
        >
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { searchStudents, findStudentById, deactivateStudent } from '@/apis/student'
import { useUserInfoStore } from '@/stores/userInfo'
import type {
  SearchStudentByCriteriaResponse,
  Student,
  StudentSearchCriteria
} from '@/apis/student/types'
import { useSettingsStore } from '@/stores/settings'
import EditStudentForm from './EditStudentForm.vue'
import { ElMessage } from 'element-plus'

const searchCriteria = ref<StudentSearchCriteria>({
  sectionId: NaN,
  firstName: '',
  lastName: ''
})

const students = ref<Student[]>([]) // Array of loaded students
const loading = ref<boolean>(true) // Loading status
const settingsStore = useSettingsStore()
const userInfo = useUserInfoStore()
const deactivationVisible = ref(false)
const deactivationTarget = ref<Student>()
const deactivating = ref(false)
const deactivationError = ref('')
const refreshWarning = ref('')
let loadRequest = 0

// Pagination information
const pageNumber = ref<number>(1) // current page number, starting from 1
const pageSize = ref<number>(50) // number of elements per page
const totalElements = ref<number>(60) // total number of elements

// Load data when the component is mounted
onMounted(() => {
  searchCriteria.value.sectionId = settingsStore.defaultSectionId
  if (Number.isNaN(searchCriteria.value.sectionId)) {
    ElMessage.error('Please select a section in the Sections page.')
    return
  }
  loadStudents().catch(() => {})
})

async function loadStudents() {
  const requestId = ++loadRequest
  loading.value = true
  try {
    const result: SearchStudentByCriteriaResponse = await searchStudents(
      { page: pageNumber.value - 1, size: pageSize.value },
      searchCriteria.value
    )
    if (requestId !== loadRequest) return
    students.value = result.data.content
    pageNumber.value = result.data.number + 1
    pageSize.value = result.data.size
    totalElements.value = result.data.totalElements
  } finally {
    if (requestId === loadRequest) loading.value = false
  }
}

async function openDeactivation(student: Student) {
  if (deactivating.value) return
  try {
    const result = await findStudentById(student.id!)
    deactivationTarget.value = result.data
    deactivationError.value = ''
    deactivationVisible.value = true
  } catch {
    /* Shared client displays the lookup refusal. */
  }
}

async function reloadDeactivation() {
  if (deactivationTarget.value) await openDeactivation(deactivationTarget.value)
}

async function confirmDeactivation() {
  if (deactivating.value || !deactivationTarget.value) return
  deactivating.value = true
  deactivationError.value = ''
  refreshWarning.value = ''
  try {
    const result = await deactivateStudent(deactivationTarget.value.id!)
    if (!result.flag) throw new Error(result.message)
    ++loadRequest // Invalidate roster reads that began before acknowledgement.
    const row = students.value.find((student) => student.id === result.data.id)
    if (row) Object.assign(row, result.data)
    deactivationTarget.value = result.data
    deactivationVisible.value = false
    ElMessage.success('Student deactivated successfully')
    try {
      await loadStudents()
    } catch {
      refreshWarning.value =
        'Student deactivated. The list could not be refreshed; try refreshing again.'
    }
  } catch {
    deactivationError.value =
      'Deactivation was not acknowledged. Reload the current state or retry; the request may already have succeeded.'
  } finally {
    deactivating.value = false
    loading.value = false
  }
}

function resetSearchCriteria() {
  searchCriteria.value = {
    sectionId: settingsStore.defaultSectionId,
    firstName: '',
    lastName: ''
  }
  loadStudents().catch(() => {})
}

function handlePageNumberChange(newPageNumer: number) {
  pageNumber.value = newPageNumer // Not necessary since pageNumber already has a two-way binding
  loadStudents().catch(() => {})
}

function handlePageSizeChange(newSize: number) {
  pageSize.value = newSize // Not necessary since pageSize already has a two-way binding
  pageNumber.value = 1 // Reset the page number to 1 when the page size changes
  loadStudents().catch(() => {})
}

// Dialog for adding or editing artifacts
const dialogVisible = ref(false)

const studentBeingEditedId = ref<number>() // ID of the artifact being edited, it will be passed to the EditArtifactForm component as a prop

function showEditStudentDialog(student: Student) {
  dialogVisible.value = true
  studentBeingEditedId.value = student.id as number
}

function closeDialog() {
  dialogVisible.value = false
  loadStudents().catch(() => {})
}

import { useRouter } from 'vue-router'

const router = useRouter()

function goToStudentPerformanceDashboard(student: Student) {
  // Redirect to the student performance dashboard page
  // The student ID can be passed as a query parameter
  router.push({
    name: 'student-performance-dashboard',
    params: { studentId: student.id }
  })
}
</script>

<style lang="scss" scoped>
.page-container {
  min-height: 100%;
  box-sizing: border-box;

  .header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
}
</style>
