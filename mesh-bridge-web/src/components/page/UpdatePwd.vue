<template>
    <div id="update-pwd">
        <div class="crumbs">
            <el-breadcrumb separator="/">
                <el-breadcrumb-item>{{ $t('common.setting') }}</el-breadcrumb-item>
                <el-breadcrumb-item>{{
                        $t('home.update_password')
                    }}
                </el-breadcrumb-item>
            </el-breadcrumb>
        </div>
        <div class="container" style="padding: 20px">
            <el-form :model="updatePasswordForm">
                <el-form-item v-bind:label="$t('setting.original_password')">
                    <el-input v-model.trim="updatePasswordForm.oldPwd" type="password"></el-input>
                </el-form-item>
                <el-form-item v-bind:label="$t('setting.new_password')">
                    <el-input v-model.trim="updatePasswordForm.newPwd" type="password"></el-input>
                </el-form-item>
                <el-form-item v-bind:label="$t('setting.confirm_new_password')">
                    <el-input v-model.trim="updatePasswordForm.newPwdRe" type="password"></el-input>
                </el-form-item>
                <el-button @click="save" :loading="loading">{{
                        $t('common.save')
                    }}
                </el-button>
            </el-form>
        </div>
    </div>
</template>

<script setup>
import {getCurrentInstance, reactive, ref} from "vue";
import {useMeshBridgeStore} from "../../store/store";
import {useI18n} from "vue-i18n";

// https://stackoverflow.com/questions/61452458/ref-vs-reactive-in-vue-3
const loading = ref(false)
const updatePasswordForm = reactive({
    oldPwd: '',
    newPwd: '',
    newPwdRe: '',
})
const {proxy} = getCurrentInstance()
const store = useMeshBridgeStore()
const {t} = useI18n()

const save = () => {
    if (updatePasswordForm.newPwd !== updatePasswordForm.newPwdRe) {
        proxy.$message(t('setting.new_password_error'))
        return
    }
    loading.value = true
    store.updatePassword(updatePasswordForm.oldPwd, updatePasswordForm.newPwd)
        .then(() => {
            proxy.$message(t('common.action_success'))
        })
        .finally(() => {
            loading.value = false
        })
}
</script>

<style></style>
