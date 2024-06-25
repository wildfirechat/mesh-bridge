<template>
    <el-main class="hello">
        <h2>IM域列表</h2>
        <div>
            <el-row v-if="imDomainList && imDomainList.length > 0" :gutter="20">
                <el-col :span="6" v-for="(imDomain, index) in imDomainList" :key="index">
                    <AppCard :im-domain="imDomain" @click.native="showIMDomainInfo(imDomain)"/>
                </el-col>
            </el-row>
            <el-empty v-else description="暂无IM域" image="">
                <el-button @click="addIMDomain()" type="primary">添加IM域</el-button>
            </el-empty>
        </div>
        <el-dialog title="IM域信息" v-model="imDomainInfoDialogVisible">
            <el-form :model="imDomainInfo">
                <el-form-item label="域名称" :label-width="formLabelWidth">
                    <el-input
                        v-model="imDomainInfo.name"
                        disabled
                        autocomplete="off"
                    ></el-input>
                </el-form-item>
                <el-form-item label="DomainId" :label-width="formLabelWidth">
                    <el-input
                        v-model="imDomainInfo.domainId"
                        disabled
                        autocomplete="off"
                    ></el-input>
                </el-form-item>
                <el-form-item label="对方 secret" :label-width="formLabelWidth">
                    <el-input
                        v-model="imDomainInfo.secret"
                        disabled
                        autocomplete="off"
                    ></el-input>
                </el-form-item>
                <el-form-item label="己方 secret" :label-width="formLabelWidth">
                    <el-input
                        v-model="imDomainInfo.mySecret"
                        disabled
                        autocomplete="off"
                    ></el-input>
                </el-form-item>
                <el-form-item label="对方 IM 域入访 url" :label-width="formLabelWidth">
                    <el-input
                        v-model="imDomainInfo.url"
                        disabled
                        autocomplete="off"
                    ></el-input>
                </el-form-item>
                <el-form-item label="对方邮件" :label-width="formLabelWidth">
                    <el-input
                        v-model="imDomainInfo.email"
                        disabled
                        autocomplete="off"
                    ></el-input>
                </el-form-item>
                <el-form-item label="对方电话" :label-width="formLabelWidth">
                    <el-input
                        v-model="imDomainInfo.tel"
                        disabled
                        autocomplete="off"
                    ></el-input>
                </el-form-item>
                <el-form-item label="对方地址" :label-width="formLabelWidth">
                    <el-input
                        v-model="imDomainInfo.address"
                        disabled
                        autocomplete="off"
                    ></el-input>
                </el-form-item>
                <el-form-item label="详细信息" :label-width="formLabelWidth">
                    <el-input
                        v-model="imDomainInfo.detailInfo"
                        disabled
                        autocomplete="off"
                    ></el-input>
                </el-form-item>
                <el-form-item label="额外信息" :label-width="formLabelWidth">
                    <el-input
                        v-model="imDomainInfo.extra"
                        disabled
                        autocomplete="off"
                    ></el-input>
                </el-form-item>
            </el-form>
            <div slot="footer" class="dialog-footer">
                <el-button type="primary" @click="imDomainInfoDialogVisible = false"
                >确 定
                </el-button
                >
            </div>
        </el-dialog>
    </el-main>
</template>

<script setup>
import AppCard from '../common/IMDomainCard.vue'
import {computed, onMounted, ref} from "vue";
import {useMeshBridgeStore} from "../../store/store";
import {useRouter} from "vue-router";
import {storeToRefs} from "pinia";

const store = useMeshBridgeStore()
const router = useRouter()

const imDomainInfoDialogVisible = ref(false)
const imDomainInfo = ref({})
const formLabelWidth = '120px'

const {imDomainList} = storeToRefs(store)

onMounted(() => {
    store.listIMDomain()
})

const showIMDomainInfo = (imDomain) => {
    imDomainInfo.value = imDomain
    imDomainInfoDialogVisible.value = true
}

const addIMDomain = () => {
    router.replace('/dev/app')
}
</script>

<style scoped>
h1,
h2 {
    font-weight: normal;
}

>>> .el-empty__image {
    display: none;
}
</style>
