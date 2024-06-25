<template>
    <div class="im-domain-card">
        <el-avatar
            shape="square"
            :size="60"
            style="margin: 0 10px"
            :src="imDomain.portraitUrl"
        >
        </el-avatar>
        <div>
            <p class="title">{{ imDomain.domainId }}</p>
            <p class="desc">{{ imDomain.detailInfo }}</p>
        </div>
    </div>
</template>

<script setup>
import ImDomain from "../../model/imDomain";

defineProps({
    imDomain: {
        type: ImDomain,
        required: true
    }
})
</script>

<style lang="css" scoped>
.im-domain-card {
    display: flex;
    flex-direction: row;
    align-items: center;
    width: 250px;
    height: 100px;
    margin: 20px 10px;
}

.im-domain-card:hover {
    background: #eef5fe;
}

.im-domain-card div {
    flex: 1;
    height: 60px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
}

.im-domain-card .title {
    font-size: 18px;
}

.im-domain-card .desc {
    font-size: 14px;
}

>>> .el-avatar > img {
    width: 100%;
}
</style>
