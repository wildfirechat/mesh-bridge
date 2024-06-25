import Login from "../components/page/Login.vue";
import Home from "../components/common/Home.vue";
import _403 from "../components/page/403.vue";
import _404 from "../components/page/404.vue";
import UpdatePwd from "../components/page/UpdatePwd.vue";
import IMDomain from "../components/page/dev/IMDomain.vue";
import index from "../components/page/Index.vue";

const routers = [
    {
        path: '/',
        redirect: '/login',
    },
    {
        path: '/login',
        component: Login
    },
    {
        path: '/',
        component: Home,
        meta: {title: '系统首页'},
        children: [
            {
                path: '/index',
                component: index,
                meta: {title: '野火IM服务互通管理平台'}
            },
            {
                path: '/dev/imdomain',
                component: IMDomain,
                meta: {title: 'IM 域'},
            },
            // more
            {
                path: '/updatePwd',
                component: UpdatePwd,
                meta: {title: '更新密码'},
            },
        ],
    },
    {
        path: '/403',
        component: _403
    },
    {
        path: '/404',
        component: _404
    },
    {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: _404
    },
]

export default routers
