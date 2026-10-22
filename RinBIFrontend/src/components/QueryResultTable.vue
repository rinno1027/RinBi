<template>
  <section v-if="table && table.columns && table.columns.length" class="result-block">
    <header class="result-head">
      <div class="result-title">
        <span class="result-dot"></span>
        <span>数据明细</span>
      </div>
      <div class="result-tags">
        <el-tag size="small" effect="plain" type="info">
          {{ displayRowCount }} 行
        </el-tag>
        <el-tag v-if="truncated" size="small" effect="light" type="warning">
          已截断
        </el-tag>
      </div>
    </header>

    <el-table
      :data="pageRows"
      size="small"
      stripe
      border
      max-height="420"
      empty-text="暂无数据"
      class="result-table"
    >
      <el-table-column type="index" label="#" width="56" :index="rowIndex" fixed />
      <el-table-column
        v-for="(column, columnIndex) in table.columns"
        :key="`${column}-${columnIndex}`"
        :label="column"
        :min-width="columnWidth(column)"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          {{ formatCell(row[columnIndex]) }}
        </template>
      </el-table-column>
    </el-table>

    <div
      v-if="Array.isArray(table.rows) && table.rows.length > pageSize"
      class="result-pagination"
    >
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="table.rows.length"
        layout="total, sizes, prev, pager, next"
        background
        small
        @size-change="handleSizeChange"
      />
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { formatCell } from '../utils/format'

const props = defineProps({
  table: {
    type: Object,
    default: null
  },
  rowCount: {
    type: Number,
    default: null
  },
  truncated: {
    type: Boolean,
    default: false
  }
})

const currentPage = ref(1)
const pageSize = ref(20)

const pageRows = computed(() => {
  if (!props.table || !Array.isArray(props.table.rows)) {
    return []
  }
  const start = (currentPage.value - 1) * pageSize.value
  return props.table.rows.slice(start, start + pageSize.value)
})

const displayRowCount = computed(() => {
  if (Number.isFinite(props.rowCount)) {
    return props.rowCount
  }
  return props.table && Array.isArray(props.table.rows) ? props.table.rows.length : 0
})

function rowIndex(index) {
  return (currentPage.value - 1) * pageSize.value + index + 1
}

function columnWidth(column) {
  const length = String(column || '').length
  return Math.min(Math.max(120, length * 18 + 48), 260)
}

function handleSizeChange() {
  currentPage.value = 1
}

watch(
  () => props.table,
  () => {
    currentPage.value = 1
  }
)
</script>
