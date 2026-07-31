import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, Info, Plus, RotateCcw, UserRound } from 'lucide-react'
import Button from '@/components/ui/Button'
import Card from '@/components/ui/Card'
import ConfirmDialog from '@/components/ui/ConfirmDialog'
import EmptyState from '@/components/ui/EmptyState'
import Spinner from '@/components/ui/Spinner'
import { useToast } from '@/components/ui'
import { ACCOUNT_STATUS, ROLES, SIGNUP_STATUS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { cn } from '@/shared/lib/cn'
import {
  createDepartment,
  deleteDepartment,
  fetchDepartmentMembers,
  fetchDepartments,
  updateDepartment,
} from '../api'

const AVATAR_TONES = [
  'bg-blue-100 text-blue-700',
  'bg-rose-100 text-rose-700',
  'bg-emerald-100 text-emerald-700',
  'bg-amber-100 text-amber-700',
  'bg-violet-100 text-violet-700',
  'bg-cyan-100 text-cyan-700',
]

function DepartmentAvatar({ department, index }) {
  return (
    <span className={cn('flex size-8 items-center justify-center rounded-xl text-sm font-bold', AVATAR_TONES[index % AVATAR_TONES.length])}>
      {department.name.slice(0, 1)}
    </span>
  )
}

export default function DepartmentManagementPage() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [newName, setNewName] = useState('')
  const [newManagerId, setNewManagerId] = useState('')
  const [managerDrafts, setManagerDrafts] = useState({})
  const [deleteTarget, setDeleteTarget] = useState(null)

  const departmentsQuery = useQuery({
    queryKey: qk.departments.list,
    queryFn: fetchDepartments,
  })
  const membersQuery = useQuery({
    queryKey: qk.users.list({ departmentManagement: true }),
    queryFn: fetchDepartmentMembers,
  })

  const departments = useMemo(
    () => departmentsQuery.data ?? [],
    [departmentsQuery.data],
  )
  const members = useMemo(
    () => membersQuery.data ?? [],
    [membersQuery.data],
  )
  const managerCandidates = members.filter(
    (member) =>
      member.role === ROLES.ADMIN &&
      member.signupStatus === SIGNUP_STATUS.APPROVED &&
      member.accountStatus === ACCOUNT_STATUS.ACTIVE,
  )

  useEffect(() => {
    setManagerDrafts(
      Object.fromEntries(
        departments.map((department) => [
          department.departmentId,
          department.manager?.userId ?? '',
        ]),
      ),
    )
  }, [departments])

  const memberCounts = useMemo(() => {
    const counts = {}
    members.forEach((member) => {
      const id = member.department?.departmentId
      if (id) counts[id] = (counts[id] ?? 0) + 1
    })
    return counts
  }, [members])

  const totalMembers = departments.reduce(
    (total, department) =>
      total + (department.memberCount ?? memberCounts[department.departmentId] ?? 0),
    0,
  )

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: qk.departments.all })
    await queryClient.invalidateQueries({ queryKey: qk.users.all })
  }

  const createMutation = useMutation({
    mutationFn: () =>
      createDepartment({
        name: newName.trim(),
        managerId: newManagerId || null,
      }),
    onSuccess: async () => {
      setNewName('')
      setNewManagerId('')
      await refresh()
      toast.success('부서가 추가되었습니다.')
    },
    onError: (error) => toast.error(error.message ?? '부서를 추가하지 못했습니다.'),
  })

  const saveMutation = useMutation({
    mutationFn: async () => {
      const changed = departments.filter(
        (department) =>
          (department.manager?.userId ?? '') !==
          (managerDrafts[department.departmentId] ?? ''),
      )
      await Promise.all(
        changed.map((department) =>
          updateDepartment(department.departmentId, {
            managerId: managerDrafts[department.departmentId] || null,
          }),
        ),
      )
      return changed.length
    },
    onSuccess: async (changedCount) => {
      await refresh()
      toast.success(
        changedCount ? '관리자 지정이 저장되었습니다.' : '변경된 내용이 없습니다.',
      )
    },
    onError: (error) => toast.error(error.message ?? '변경사항을 저장하지 못했습니다.'),
  })

  const deleteMutation = useMutation({
    mutationFn: () => deleteDepartment(deleteTarget.departmentId),
    onSuccess: async () => {
      setDeleteTarget(null)
      await refresh()
      toast.success('부서가 삭제되었습니다.')
    },
    onError: (error) => {
      setDeleteTarget(null)
      toast.error(error.message ?? '부서를 삭제하지 못했습니다.')
    },
  })

  const resetDrafts = () => {
    setManagerDrafts(
      Object.fromEntries(
        departments.map((department) => [
          department.departmentId,
          department.manager?.userId ?? '',
        ]),
      ),
    )
  }

  if (departmentsQuery.isLoading || membersQuery.isLoading) {
    return <div className="flex justify-center py-24"><Spinner /></div>
  }

  return (
    <div className="space-y-5">
      <Card className="overflow-hidden">
        <div className="flex flex-wrap items-start justify-between gap-4 px-5 py-4">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-bold text-slate-900">부서 현황</h2>
              <span className="rounded-full bg-primary-50 px-2.5 py-1 text-xs font-semibold text-primary-600">
                {departments.length}개
              </span>
            </div>
            <p className="mt-1 text-sm text-slate-400">
              부서를 추가·삭제하고, 부서별 관리자를 지정할 수 있습니다.
            </p>
          </div>
          <div className="rounded-xl bg-slate-100 px-4 py-2 text-sm text-slate-500">
            소속 인원 <strong className="ml-2 text-slate-900">{totalMembers}명</strong>
          </div>
        </div>

        <form
          className="mx-5 mb-4 flex flex-wrap items-center gap-3 rounded-xl border border-primary-200 bg-primary-50/40 p-3"
          onSubmit={(event) => {
            event.preventDefault()
            if (!newName.trim()) {
              toast.error('추가할 부서명을 입력해주세요.')
              return
            }
            createMutation.mutate()
          }}
        >
          <div className="flex shrink-0 items-center gap-2 font-semibold text-slate-800">
            <span className="flex size-8 items-center justify-center rounded-lg bg-primary-600 text-white">
              <Plus className="size-4" />
            </span>
            부서 추가
          </div>
          <input
            value={newName}
            onChange={(event) => setNewName(event.target.value)}
            placeholder="추가할 부서명 입력"
            className="focus-ring h-10 min-w-56 flex-1 rounded-lg border border-slate-200 bg-white px-3 text-sm"
          />
          <select
            value={newManagerId}
            onChange={(event) => setNewManagerId(event.target.value)}
            className="focus-ring h-10 min-w-56 rounded-lg border border-slate-200 bg-white px-3 text-sm text-slate-600"
          >
            <option value="">부서 관리자 선택 (선택)</option>
            {managerCandidates.map((manager) => (
              <option key={manager.userId} value={manager.userId}>{manager.name}</option>
            ))}
          </select>
          <Button type="submit" loading={createMutation.isPending}>부서 추가</Button>
        </form>

        {departments.length === 0 ? (
          <EmptyState title="등록된 부서가 없습니다." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-y border-slate-100 bg-slate-50 text-xs text-slate-500">
                <tr>
                  <th className="px-5 py-3 text-center">부서명</th>
                  <th className="px-5 py-3 text-center">인원 수</th>
                  <th className="px-5 py-3 text-center">부서 관리자</th>
                  <th className="px-5 py-3 text-center">관리</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {departments.map((department, index) => {
                  const count =
                    department.memberCount ??
                    memberCounts[department.departmentId] ??
                    0
                  const selectedManager = managerCandidates.find(
                    (manager) =>
                      manager.userId ===
                      (managerDrafts[department.departmentId] ?? ''),
                  )
                  return (
                    <tr key={department.departmentId}>
                      <td className="px-5 py-3">
                        <div className="flex items-center gap-3">
                          <DepartmentAvatar department={department} index={index} />
                          <span className="font-semibold text-slate-800">{department.name}</span>
                        </div>
                      </td>
                      <td className="px-5 py-3 text-center font-semibold text-slate-700">{count}명</td>
                      <td className="px-5 py-3 text-center">
                        <div className="relative mx-auto max-w-xs text-left">
                          <span
                            className={cn(
                              'pointer-events-none absolute inset-y-0 left-2 my-auto flex size-7 items-center justify-center rounded-full text-xs font-bold',
                              selectedManager
                                ? 'bg-primary-600 text-white'
                                : 'border border-dashed border-slate-300 bg-white text-slate-400',
                            )}
                          >
                            {selectedManager ? (
                              selectedManager.name.slice(0, 1)
                            ) : (
                              <UserRound className="size-4" />
                            )}
                          </span>
                          <select
                            value={managerDrafts[department.departmentId] ?? ''}
                            onChange={(event) =>
                              setManagerDrafts({
                                ...managerDrafts,
                                [department.departmentId]: event.target.value,
                              })
                            }
                            className="focus-ring h-10 w-full appearance-none rounded-lg border border-slate-200 bg-white pl-11 pr-9 text-sm text-slate-700"
                          >
                            <option value="">관리자 미지정</option>
                            {managerCandidates.map((manager) => (
                              <option key={manager.userId} value={manager.userId}>
                                {manager.name}
                              </option>
                            ))}
                          </select>
                          <ChevronDown className="pointer-events-none absolute inset-y-0 right-3 my-auto size-4 text-slate-400" />
                        </div>
                      </td>
                      <td className="px-5 py-3 text-center">
                        <span className="group relative inline-flex">
                          <Button
                            size="sm"
                            variant="outline"
                            className="border-rose-200 text-rose-600 hover:bg-rose-50"
                            disabled={count > 0}
                            onClick={() => setDeleteTarget(department)}
                          >
                            삭제
                          </Button>
                          {count > 0 && (
                            <span
                              role="tooltip"
                              className="pointer-events-none absolute bottom-[calc(100%+8px)] right-0 z-20 hidden whitespace-nowrap rounded-lg bg-slate-900 px-3 py-2 text-xs font-medium text-white shadow-lg group-hover:block"
                            >
                              소속 인원 {count}명이 있어 삭제할 수 없습니다.
                              <span className="absolute -bottom-1 right-5 size-2 rotate-45 bg-slate-900" />
                            </span>
                          )}
                        </span>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}

        <div className="flex items-center gap-2 border-t border-slate-100 bg-primary-50/40 px-5 py-3 text-xs text-slate-500">
          <Info className="size-4 text-primary-500" />
          부서 안에 사원이 1명이라도 남아있으면, 부서 삭제가 불가능합니다.
        </div>
      </Card>

      <div className="flex flex-wrap items-center justify-between gap-3 px-1">
        <p className="text-xs text-slate-400">변경한 관리자 지정은 저장해야 반영됩니다.</p>
        <div className="flex gap-2">
          <Button variant="outline" onClick={resetDrafts}>
            <RotateCcw className="size-4" /> 되돌리기
          </Button>
          <Button loading={saveMutation.isPending} onClick={() => saveMutation.mutate()}>
            변경사항 저장
          </Button>
        </div>
      </div>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => deleteMutation.mutate()}
        title="부서를 삭제할까요?"
        description={`${deleteTarget?.name ?? ''} 부서는 삭제 후 복구할 수 없습니다.`}
        confirmLabel="삭제"
        tone="danger"
        loading={deleteMutation.isPending}
      />
    </div>
  )
}
