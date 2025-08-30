import {defineStore} from "pinia";
import api from "../api/api";

// options api
export const useMeshBridgeStore = defineStore('meshBridge', {
    state: () => ({imDomainList: [], admin: ''}),
    getters: {
        helloAdmin: (state) => 'hello ' + state.admin,
        // TODO more
    },
    actions: {
        getAccount() {
            api.getAccount()
                .then(account => {
                    this.admin = account
                })
        },
        login(userName, password) {
            return api.login(userName, password)
        },
        updatePassword(oldPassword, newPassword) {
            return api.updatePassword(oldPassword, newPassword)
        },
        updateAdminUser(user) {
            // TODO
        },
        createIMDomain(imDomain) {
            api.createIMDomain(imDomain)
                .then(res => {
                    this.listIMDomain()
                })
        },
        updateIMDomain(imDomain) {
            api.updateIMDomain(imDomain)
                .then(() => {
                    this.listIMDomain()
                })
        },
        listIMDomain() {
            api.listIMDomain()
                .then(imDomains => {
                    this.imDomainList = imDomains;
                })
        },
        deleteIMDomain(domainId) {
            api.deleteDomain(domainId)
                .then(() => {
                    this.listIMDomain()
                })
        },
        pingIMDomain(domainId) {
            api.pingDomain(domainId)
                .then(res => {
                    console.log(res);
                    alert(res);
                })
        },
    },
})
