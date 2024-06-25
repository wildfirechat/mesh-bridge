import vue from '@vitejs/plugin-vue'

const path = require("path");

export default {
    resolve: {
        alias: {
            "@": path.resolve(__dirname, "./src"),
        },
    },
    build: {
        commonjsOptions: {transformMixedEsModules: true} // Change
    },
    plugins: [
        vue({
            template: {
                compilerOptions: {
                    compatConfig: {
                        MODE: 3,
                    },
                },
            },
        }),
    ],
}
