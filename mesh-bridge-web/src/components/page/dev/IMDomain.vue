<template>
    <div style="height: 100%">
        <el-main>
            <el-card>
                <h2>IM域</h2>
                <div style="display: flex; flex-direction: row; justify-content: space-between; align-items: center">
                    <p>开发文档请看
                        <el-link href="https://github.com/wildfirechat/mesh-bridge" style="flex: 1" target="_blank" type="primary">开发文档</el-link>
                    </p>
                    <el-button type="primary" @click="imDomainInfo = {}; createAppDialogVisible = true">添加 IM 域</el-button>
                </div>
                <el-row :gutter="20" v-if="imDomainList && imDomainList.length > 0">
                    <el-col :span="6" v-for="(imDomain, index) in imDomainList" :key="index" @click.native="showIMDomainInfo(imDomain)">
                        <AppCard :im-domain="imDomain"/>
                    </el-col>
                </el-row>
                <el-empty v-else description="暂无IM域" image=""></el-empty>
            </el-card>
            <el-dialog title=" 添加IM域" v-model="createAppDialogVisible">
                <el-form :model="imDomainInfo" :rules="rules" ref="createDomainForm">
                    <el-form-item label="域名称" :label-width="formLabelWidth" prop="name">
                        <el-input v-model.trim="imDomainInfo.name" autocomplete="off" placeholder="域名称"></el-input>
                    </el-form-item>
                    <el-form-item label="域ID" :label-width="formLabelWidth" prop="domainId">
                        <el-input v-model.trim="imDomainInfo.domainId" autocomplete="off" placeholder="域的 ID，不能重复，类似 wildfirechat.cn"></el-input>
                    </el-form-item>
                    <el-form-item label="域描述" :label-width="formLabelWidth" prop="detailInfo">
                        <el-input v-model.trim="imDomainInfo.detailInfo" autocomplete="off" placeholder="域的一句话描述"></el-input>
                    </el-form-item>
                    <el-form-item label="对应密钥" :label-width="formLabelWidth" prop="secret">
                        <el-input v-model.trim="imDomainInfo.secret" autocomplete="off" placeholder="对方 IM Domain 的 密钥，需要对方创建 IM Domain 之后，才知道"></el-input>
                    </el-form-item>
                    <el-form-item label="对方 IM Bridge 入访url" :label-width="formLabelWidth" prop="url">
                        <el-input v-model.trim="imDomainInfo.url" autocomplete="off" placeholder="对方 IM Bridge 的地址，默认应当是 http://${对方ip}:8200/api"></el-input>
                    </el-form-item>
                    <el-form-item label="对方email" :label-width="formLabelWidth" prop="email">
                        <el-input v-model.trim="imDomainInfo.email" autocomplete="off" placeholder="对方的邮箱地址"></el-input>
                    </el-form-item>
                    <el-form-item label="对方电话" :label-width="formLabelWidth" prop="tel">
                        <el-input v-model.trim="imDomainInfo.tel" autocomplete="off" placeholder="对方的电话"></el-input>
                    </el-form-item>
                    <el-form-item label="对方地址" :label-width="formLabelWidth" prop="address">
                        <el-input v-model.trim="imDomainInfo.address" autocomplete="off" placeholder="对方的地址"></el-input>
                    </el-form-item>
                    <el-form-item label="额外信息" :label-width="formLabelWidth" prop="extra">
                        <el-input v-model.trim="imDomainInfo.extra" autocomplete="off" placeholder="其他信息"></el-input>
                    </el-form-item>
                </el-form>
                <div slot="footer" class="dialog-footer">
                    <el-button @click="createAppDialogVisible = false">取 消</el-button>
                    <el-button type="primary" @click="createDomain('createAppForm')">确 定</el-button>
                </div>
            </el-dialog>

            <el-dialog title="修改IM域" v-model="modifyAppDialogVisible">
                <el-form :model="imDomainInfo" :rules="rules" ref="updateDomainForm">
                    <el-form-item label="域名称" :label-width="formLabelWidth" prop="name">
                        <el-input v-model.trim="imDomainInfo.name" autocomplete="off" placeholder="域名称"></el-input>
                    </el-form-item>
                    <el-form-item label="域ID" :label-width="formLabelWidth" prop="domainId">
                        <el-input disabled v-model.trim="imDomainInfo.domainId" autocomplete="off" placeholder="域的 ID，不能重复，类似 wildfirechat.cn"></el-input>
                    </el-form-item>
                    <el-form-item label="域描述" :label-width="formLabelWidth" prop="detailInfo">
                        <el-input v-model.trim="imDomainInfo.detailInfo" autocomplete="off" placeholder="域的一句话描述"></el-input>
                    </el-form-item>
                    <el-form-item label="对方密钥" :label-width="formLabelWidth" prop="secret">
                        <el-input v-model.trim="imDomainInfo.secret" autocomplete="off" placeholder="对方 IM Domain 的 密钥"></el-input>
                    </el-form-item>
                    <el-form-item label="己方密钥" :label-width="formLabelWidth" prop="secret">
                        <el-input disabled v-model.trim="imDomainInfo.mySecret" autocomplete="off" placeholder="己方 IM Domain 的 密钥"></el-input>
                    </el-form-item>
                    <el-form-item label="对方 IM Bridge 入访 url" :label-width="formLabelWidth" prop="url">
                        <el-input v-model.trim="imDomainInfo.url" autocomplete="off" placeholder="对方 IM Bridge 入访的地址，默认应当是 http://${对方ip}:8200/api"></el-input>
                    </el-form-item>
                    <el-form-item label="对方email" :label-width="formLabelWidth" prop="email">
                        <el-input v-model.trim="imDomainInfo.email" autocomplete="off" placeholder="对方的邮箱地址"></el-input>
                    </el-form-item>
                    <el-form-item label="对方电话" :label-width="formLabelWidth" prop="tel">
                        <el-input v-model.trim="imDomainInfo.tel" autocomplete="off" placeholder="对方的电话"></el-input>
                    </el-form-item>
                    <el-form-item label="对方地址" :label-width="formLabelWidth" prop="address">
                        <el-input v-model.trim="imDomainInfo.address" autocomplete="off" placeholder="对方的地址"></el-input>
                    </el-form-item>
                    <el-form-item label="额外信息" :label-width="formLabelWidth" prop="extra">
                        <el-input v-model.trim="imDomainInfo.extra" autocomplete="off" placeholder="额外信息"></el-input>
                    </el-form-item>
                </el-form>
                <div slot="footer" class="dialog-footer">
                    <el-button @click="modifyAppDialogVisible = false">取 消</el-button>
                    <el-button type="danger" @click="deleteDomain">删 除</el-button>
                    <el-button type="primary" @click="updateDomain('updateDomainForm')">修 改</el-button>
                </div>
            </el-dialog>
        </el-main>
    </div>
</template>

<script setup>
import AppCard from "../../common/IMDomainCard.vue";
import {getCurrentInstance, onMounted, ref} from "vue";
import {useMeshBridgeStore} from "../../../store/store";
import {storeToRefs} from "pinia";

const createAppDialogVisible = ref(false)
const modifyAppDialogVisible = ref(false)
const imDomainInfo = ref({})
const createDomainForm = ref(null)
const updateDomainForm = ref(null)
const formLabelWidth = '140px'
const store = useMeshBridgeStore()

const {imDomainList} = storeToRefs(store)

const rules = {
    domainId: [
        {required: true, message: '请输入domain id', trigger: 'blur'},
        {min: 1, max: 20, message: '长度在 1 到 20 个字符', trigger: 'blur'}
    ],
    name: [
        {required: true, message: '请输入名称', trigger: 'blur'},
        {min: 1, max: 10, message: '长度在 1 到 10 个字符', trigger: 'blur'}
    ],
    detailInfo: [
        {required: true, message: '请输入描述', trigger: 'blur'},
        {min: 1, max: 20, message: '长度在 1 到 20 个字符', trigger: 'blur'}
    ],
    email: [
        {required: false, message: '请输入邮箱地址', trigger: 'blur'},
    ],
    tel: [
        {required: false, message: '请输入电话号码', trigger: 'blur'},
    ],
    address: [
        {required: false, message: '请输入地址', trigger: 'blur'},
    ],
    url: [
        {required: false, message: '请输入对方 IM Bridge 入访地址', trigger: 'blur'},
    ],
}

onMounted(() => {
    store.listIMDomain()
})

const createDomain = (formName) => {
    console.log('submitForm', formName)
    createDomainForm.value.validate((valid) => {
        if (valid) {
            createAppDialogVisible.value = false;
            store.createIMDomain(imDomainInfo.value)
            imDomainInfo.value = {}
        } else {
            console.log('error submit!!');
            return false;
        }
    });
}
const showIMDomainInfo = (imdomain) => {
    imDomainInfo.value = imdomain;
    modifyAppDialogVisible.value = true;
}
const updateDomain = (formName) => {
    updateDomainForm.value.validate((valid) => {
        if (valid) {
            modifyAppDialogVisible.value = false;
            store.updateIMDomain(imDomainInfo.value)
            imDomainInfo.value = {}
        } else {
            console.log('error submit!!');
            return false;
        }
    });

}
const deleteDomain = () => {
    modifyAppDialogVisible.value = false;
    store.deleteIMDomain(imDomainInfo.value.domainId)
}

</script>

<style scoped>
.create-button-container {
    display: flex;
    width: 250px;
    height: 100px;
    margin: 20px 10px;
    justify-content: center;
    align-items: center;
}

.create-button-container .button {
    padding: 20px 30px;
}

>>> .el-empty__image {
    display: none;
}
</style>