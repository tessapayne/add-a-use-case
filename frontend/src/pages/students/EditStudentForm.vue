<template>
  <el-form
    ref="studentForm"
    :model="studentBeingEdited"
    :rules="rules"
    label-width="110px"
    style="padding-right: 30px"
    label-position="left"
  >
    <el-form-item label="Id:">
      <el-input v-model="studentBeingEdited.id" disabled></el-input>
    </el-form-item>
    <el-form-item label="Username:" prop="username">
      <el-input v-model="studentBeingEdited.username"></el-input>
    </el-form-item>
    <el-form-item label="Status:" prop="enabled">
      <el-tag
        data-cy="student-profile-status"
        :type="studentBeingEdited.enabled ? 'success' : 'danger'"
      >
        {{ studentBeingEdited.enabled ? 'Active' : 'Inactive' }}
      </el-tag>
    </el-form-item>
    <el-form-item label="First Name:" prop="firstName">
      <el-input v-model="studentBeingEdited.firstName"></el-input>
    </el-form-item>
    <el-form-item label="Last Name:" prop="lastName">
      <el-input v-model="studentBeingEdited.lastName"></el-input>
    </el-form-item>
    <el-form-item label="Email:" prop="email">
      <el-input v-model="studentBeingEdited.email"></el-input>
    </el-form-item>
    <el-form-item label="Team:" prop="teamName">
      <el-input v-model="studentBeingEdited.teamName" disabled></el-input>
    </el-form-item>
    <div style="display: flex; justify-content: end">
      <el-button :disabled="saving" @click="$emit('close-dialog')"> Cancel </el-button>
      <el-button
        data-cy="student-profile-save"
        type="primary"
        :disabled="saving"
        :loading="saving"
        @click="updateExistingStudent()"
        >Save changes</el-button
      >
      <el-button
        v-if="studentBeingEdited.enabled === false && (userInfo.isInstructor || userInfo.isAdmin)"
        data-cy="student-restore"
        type="warning"
        :disabled="saving"
        @click="updateExistingStudent(true)"
        >Save changes and reactivate</el-button
      >
    </div>
  </el-form>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserInfoStore } from '@/stores/userInfo'
import type { Student } from '@/apis/student/types'
import type { FormInstance } from 'element-plus'
import { findStudentById, updateStudent } from '@/apis/student'
import { ref, onMounted } from 'vue'

const { studentId } = defineProps(['studentId'])
const emit = defineEmits(['close-dialog'])
const saving = ref(false)
const userInfo = useUserInfoStore()

// Note that the structure of the userData is NOT the same as the User interface since the roles are stored as an array
const studentBeingEdited = ref<Student>({
  // Data of the user being added or edited
  id: NaN,
  username: '',
  enabled: false,
  firstName: '',
  lastName: '',
  email: ''
})

const studentForm = ref<FormInstance>() // Form reference

// Validation rules
const rules = {
  username: [
    { required: true, message: 'Please input your email as your username', trigger: 'blur' },
    {
      pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
      message: 'Enter your email as username.',
      trigger: 'blur'
    }
  ],
  firstName: [
    { required: true, message: 'Please provide the first name of the user.', trigger: 'blur' }
  ],
  lastName: [
    { required: true, message: 'Please provide the last name of the user.', trigger: 'blur' }
  ],
  email: [
    { required: true, message: 'Please provide the email of the user.', trigger: 'blur' },
    { type: 'email', message: 'Please provide a valid email.', trigger: 'blur' }
  ]
}

async function loadStudent(studentId: number) {
  // Load the user to be edited
  const result = await findStudentById(studentId)

  studentBeingEdited.value.id = result.data.id as number
  studentBeingEdited.value.username = result.data.username
  studentBeingEdited.value.enabled = result.data.enabled
  studentBeingEdited.value.firstName = result.data.firstName
  studentBeingEdited.value.lastName = result.data.lastName
  studentBeingEdited.value.email = result.data.email
  studentBeingEdited.value.teamName = result.data.teamName
}

onMounted(() => {
  loadStudent(studentId).catch(() => {})
})

async function updateExistingStudent(reactivate = false) {
  if (saving.value) return
  saving.value = true
  try {
    await studentForm.value!.validate()
    if (reactivate) {
      try {
        await ElMessageBox.confirm(
          "Save these profile changes and restore this student's access?",
          'Reactivate student',
          {
            confirmButtonText: 'Save changes and reactivate',
            cancelButtonText: 'Cancel',
            type: 'warning'
          }
        )
      } catch {
        return
      }
    }
    const result = await updateStudent({ ...studentBeingEdited.value }, reactivate)
    if (!result.flag) throw new Error(result.message)
    studentBeingEdited.value = result.data
    ElMessage.success(
      reactivate ? 'Student profile saved and access restored' : 'Student updated successfully'
    )
    emit('close-dialog')
  } catch {
    /* Validation keeps the draft; shared client reports request errors. */
  } finally {
    saving.value = false
  }
}
</script>

<style scoped lang="scss"></style>
