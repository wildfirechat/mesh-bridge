import axios from './axios.config'

export default {
    login(account, password) {
        return axios.post('/login', {
            account,
            password
        })
    },
    getAccount() {
        return axios.post('/account')
    },
    updatePassword(oldPassword, newPassword) {
        return axios.post('/update_pwd', {
            oldPassword,
            newPassword
        })
    },
    createIMDomain(imDomain) {
        return axios.post('/create_domain', imDomain)
    },

    listIMDomain() {
        return axios.post('/list_domain')
    },

    updateIMDomain(imDomain) {
        return axios.post('/update_domain', imDomain)
    },

    deleteDomain(domainId) {
        return axios.post('/delete_domain', {
            domainId
        })
    }

}
