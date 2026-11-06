<script setup lang="ts">
import TaskDetail from '../components/TaskDetail.vue'
</script>
<template><TaskDetail :key="String($route.params.id)" kind="builds" /></template>
