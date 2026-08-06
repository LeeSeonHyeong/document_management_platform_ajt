import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Info, Plus, UserRound } from 'lucide-react'
import Button from '@/components/ui/Button'
import Card from '@/components/ui/Card'
import ConfirmDialog from '@/components/ui/ConfirmDialog'
import EmptyState from '@/components/ui/EmptyState'
import Field from '@/components/ui/Field'
import Input from '@/components/ui/Input'
import Modal from '@/components/ui/Modal'
import Spinner from '@/components/ui/Spinner'
import { useToast } from '@/components/ui'
import { useAuth } from '@/hooks/useAuth'
import { ACCOUNT_STATUS, ROLES, SIGNUP_STATUS } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { cn } from '@/shared/lib/cn'
import { sanitizePlainName } from '@/shared/lib/sanitizePlainName'
import { updateUser } from '@/features/member/api'
import {
  createDepartment,
  deleteDepartment,
  fetchDepartmentMembers,
  fetchDepartments,
  updateDepartment,
} from '../api'
import { isDefaultDepartment } from '../defaultDepartment'

const AVATAR_TONES = [
  'bg-blue-100 text-blue-700',
  'bg-rose-100 text-rose-700',
  'bg-emerald-100 text-emerald-700',
  'bg-amber-100 text-amber-700',
  'bg-violet-100 text-violet-700',
  'bg-cyan-100 text-cyan-700',
]

const SYSTEM_DEPARTMENT_NAMES = new Set(['최고관리자'])
const KEEP_CURRENT_MANAGER = '__keep_current_manager__'
const DEPARTMENT_NAME_MAX_LENGTH = 20

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
  const { isSuperAdmin } = useAuth()
  const [newName, setNewName] = useState('')
  const [editing, setEditing] = useState(null)
  const [editName, setEditName] = useState('')
  const [editManagerId, setEditManagerId] = useState('')
  const [editManagerError, setEditManagerError] = useState('')
  const [deleteTarget, setDeleteTarget] = useState(null)

  const departmentsQuery = useQuery({
    queryKey: qk.departments.list,
    queryFn: fetchDepartments,
  })
  const membersQuery = useQuery({
    queryKey: qk.users.list({ departmentManagement: true }),
    queryFn: fetchDepartmentMembers,
  })

  // 기본 부서('미지정')를 맨 위에 둔다 (S15P11B106-250). 부서 없는 사람이 모이는 자리라
  // 관리자가 가장 먼저 확인해야 하고, 다른 부서와 성격이 달라 섞여 있으면 눈에 띄지 않는다.
  // 나머지 순서는 서버가 준 그대로 유지한다.
  const departments = useMemo(() => {
    const visible = (departmentsQuery.data ?? []).filter(
      (department) => !SYSTEM_DEPARTMENT_NAMES.has(department.name?.trim()),
    )
    return [
      ...visible.filter(isDefaultDepartment),
      ...visible.filter((department) => !isDefaultDepartment(department)),
    ]
  }, [departmentsQuery.data])
  const members = useMemo(
    () => membersQuery.data ?? [],
    [membersQuery.data],
  )
  const approvedActiveMembers = members.filter(
    (member) =>
      member.signupStatus === SIGNUP_STATUS.APPROVED &&
      member.accountStatus === ACCOUNT_STATUS.ACTIVE &&
      member.isSuperAdmin !== true &&
      !SYSTEM_DEPARTMENT_NAMES.has(member.department?.name?.trim()),
  )
  const editManagerCandidates = editing
    ? approvedActiveMembers.filter(
        (member) =>
          member.department?.departmentId === editing.departmentId &&
          member.userId !== editing.manager?.userId &&
          !member.isDepartmentManager,
      )
    : []

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

  // 부서를 추가·삭제하면 부서에서 파생되는 캐시도 같이 버려야 한다.
  // 위키 사이드바의 「부서」 공간 목록(wiki-spaces)과 그 아래 카테고리는 `departments` 접두 밖에
  // 있는 별도 키라, 예전에는 부서를 지우고 위키로 넘어가도 삭제 전 개수가 그대로 남아 있었다
  // (staleTime 30초 안에는 재요청도 안 하므로 새로고침 말고는 갱신될 길이 없었다).
  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: qk.departments.all }),
      queryClient.invalidateQueries({ queryKey: qk.users.all }),
      queryClient.invalidateQueries({ queryKey: qk.wikis.spaces }),
      queryClient.invalidateQueries({ queryKey: qk.wikis.categoriesAll }),
      queryClient.invalidateQueries({ queryKey: qk.wikis.listAll }),
    ])
  }

  const createMutation = useMutation({
    mutationFn: () =>
      createDepartment({
        name: newName.trim(),
        managerId: null,
      }),
    onSuccess: async () => {
      setNewName('')
      await refresh()
      toast.success('부서가 추가되었습니다.')
    },
    onError: (error) => toast.error(error.message ?? '부서를 추가하지 못했습니다.'),
  })

  const editMutation = useMutation({
    mutationFn: async () => {
      const previousManagerId = editing.manager?.userId ?? null
      const managerChanged = editManagerId !== KEEP_CURRENT_MANAGER
      const nextManager = managerChanged && editManagerId
        ? members.find((member) => member.userId === editManagerId)
        : null

      // 관리자 미지정은 기존 관리자의 역할 강등만 요청한다.
      // 백엔드가 강등과 부서 관리자 해제를 한 트랜잭션으로 처리하므로 중간 상태가 남지 않는다.
      if (managerChanged && !editManagerId && previousManagerId) {
        await updateUser(previousManagerId, { role: ROLES.EMPLOYEE })
        await updateDepartment(editing.departmentId, { name: editName.trim() })
        return null
      }

      const promotedNextManager = Boolean(nextManager && nextManager.role !== ROLES.ADMIN)
      if (promotedNextManager) {
        await updateUser(nextManager.userId, { role: ROLES.ADMIN })
      }

      // 기존 관리자는 본인이 담당하던 문의를 모두 처리해야 교체할 수 있다.
      // 강등이 거절되면 관리자 변경을 진행하지 않고, 앞서 승격한 후보도 원래 역할로 복구한다.
      if (managerChanged && previousManagerId && previousManagerId !== editManagerId) {
        try {
          await updateUser(previousManagerId, { role: ROLES.EMPLOYEE })
        } catch (error) {
          if (promotedNextManager) {
            try {
              await updateUser(nextManager.userId, { role: ROLES.EMPLOYEE })
            } catch {
              // 복구 실패보다 기존 강등 실패 원인을 우선 안내한다.
            }
          }
          throw error
        }
      }

      const departmentChanges = { name: editName.trim() }
      if (managerChanged) {
        departmentChanges.managerId = editManagerId || null
      }
      await updateDepartment(editing.departmentId, departmentChanges)
      return null
    },
    onSuccess: async () => {
      await refresh()
      setEditing(null)
      setEditManagerError('')
      toast.success('부서 정보가 수정되었습니다.')
    },
    onError: (error) => {
      if (error.code === 'INQUIRY_ASSIGNEE_HAS_PENDING') {
        setEditManagerError('* 처리되지 않은 문의가 남은 관리자는 변경하거나 미지정할 수 없습니다.')
        return
      }
      toast.error(error.message ?? '부서 정보를 수정하지 못했습니다.')
    },
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

  const openEditModal = (department) => {
    setEditing(department)
    setEditName(department.name)
    setEditManagerId(department.manager ? KEEP_CURRENT_MANAGER : '')
    setEditManagerError('')
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
              {isSuperAdmin
                ? '부서를 추가·삭제하고, 부서별 관리자를 지정할 수 있습니다.'
                : '부서별 인원과 관리자를 확인할 수 있습니다.'}
            </p>
          </div>
          <div className="rounded-xl bg-slate-100 px-4 py-2 text-sm text-slate-500">
            소속 인원 <strong className="ml-2 text-slate-900">{totalMembers}명</strong>
          </div>
        </div>

        {isSuperAdmin && <form
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
          <div className="min-w-56 flex-1">
            <input
              value={newName}
              maxLength={DEPARTMENT_NAME_MAX_LENGTH}
              onChange={(event) => setNewName(sanitizePlainName(event.target.value))}
              placeholder="추가할 부서명 입력"
              aria-invalid={newName.length >= DEPARTMENT_NAME_MAX_LENGTH}
              className="focus-ring h-10 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm"
            />
            {newName.length >= DEPARTMENT_NAME_MAX_LENGTH && (
              <p className="mt-1 text-xs text-rose-600">
                * 부서명은 최대 {DEPARTMENT_NAME_MAX_LENGTH}자까지 입력할 수 있습니다.
              </p>
            )}
          </div>
          <Button type="submit" loading={createMutation.isPending}>부서 추가</Button>
        </form>}

        {departments.length === 0 ? (
          <EmptyState title="등록된 부서가 없습니다." />
        ) : (
          <div className="overflow-x-auto border-t border-slate-200">
            <table className="w-full min-w-[760px] border-collapse text-sm">
              <thead className="border-y border-slate-100 bg-slate-50 text-xs text-slate-500">
                <tr>
                  <th className="px-5 py-3 text-center">부서명</th>
                  <th className="px-5 py-3 text-center">인원 수</th>
                  <th className="px-5 py-3 text-center">부서 관리자</th>
                  {isSuperAdmin && <th className="px-5 py-3 text-center">관리</th>}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {departments.map((department, index) => {
                  const count =
                    department.memberCount ??
                    memberCounts[department.departmentId] ??
                    0
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
                        {/* 기본 부서('미지정')는 관리자를 둘 자리가 아니다 — 「관리자 미지정」은
                            '지정할 수 있는데 안 했다'로 읽히므로 「—」로 둔다 (S15P11B106-250). */}
                        {isDefaultDepartment(department) ? (
                          <span className="text-slate-400">—</span>
                        ) : (
                          <div className="inline-flex items-center gap-2">
                            <span
                              className={cn(
                                'flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-bold',
                                department.manager
                                  ? 'bg-primary-600 text-white'
                                  : 'border border-dashed border-slate-300 bg-white text-slate-400',
                              )}
                            >
                              {department.manager ? (
                                department.manager.name.slice(0, 1)
                              ) : (
                                <UserRound className="size-4" />
                              )}
                            </span>
                            <span className={department.manager ? 'font-medium text-slate-700' : 'text-slate-400'}>
                              {department.manager?.name ?? '관리자 미지정'}
                            </span>
                          </div>
                        )}
                      </td>
                      {isSuperAdmin && (
                        <td className="px-5 py-3 text-center">
                          <div className="flex flex-nowrap items-center justify-center gap-2">
                            {/* 기본 부서는 이름도 관리자도 바꿀 수 없다 — 눌러도 실패하는 버튼을
                                두지 않는다 (S15P11B106-250). 삭제 버튼을 감추는 것과 같은 처리다. */}
                            {!isDefaultDepartment(department) && (
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() => openEditModal(department)}
                              >
                                수정
                              </Button>
                            )}

                            {isDefaultDepartment(department) ? (
                              // 시스템 기본 부서('전체')는 삭제할 수 없어 삭제 버튼을 렌더링하지 않는다(S15P11B106-146).
                              <span className="text-xs font-medium text-slate-400">기본 부서</span>
                            ) : (
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
                            )}
                          </div>
                        </td>
                      )}
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}

        {isSuperAdmin && <div className="flex items-center gap-2 border-t border-slate-100 bg-primary-50/40 px-5 py-3 text-xs text-slate-500">
          <Info className="size-4 text-primary-500" />
          부서 안에 사원이 1명이라도 남아있으면, 부서 삭제가 불가능합니다.
        </div>}
      </Card>

      <Modal
        open={Boolean(editing)}
        onClose={editMutation.isPending ? undefined : () => setEditing(null)}
        closeOnOverlay={!editMutation.isPending}
        showClose={false}
        size="lg"
        footerClassName="bg-slate-50 px-6 py-4"
        footer={
          <>
            <Button variant="outline" onClick={() => setEditing(null)} disabled={editMutation.isPending}>
              취소
            </Button>
            <Button
              onClick={() => editMutation.mutate()}
              loading={editMutation.isPending}
              disabled={!editName.trim()}
            >
              저장
            </Button>
          </>
        }
      >
        <div>
          <h2 className="text-xl font-bold text-slate-900">부서 수정</h2>
          <p className="mt-1 text-sm text-slate-500">부서명과 부서 관리자를 변경할 수 있습니다.</p>
        </div>

        <div className="mt-5 space-y-4">
          <Input
            label="부서명"
            required
            value={editName}
            onChange={(event) => setEditName(event.target.value)}
            placeholder="부서명"
          />
          <Field label="부서 관리자" error={editManagerError}>
            <select
              value={editManagerId}
              onChange={(event) => {
                setEditManagerId(event.target.value)
                setEditManagerError('')
              }}
              className="focus-ring h-10 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm text-slate-700"
            >
              {editing?.manager && (
                <option value={KEEP_CURRENT_MANAGER}>{editing.manager.name} (현재 관리자)</option>
              )}
              <option value="">관리자 미지정</option>
              {editManagerCandidates.map((manager) => (
                <option key={manager.userId} value={manager.userId}>
                  {manager.name}
                </option>
              ))}
            </select>
          </Field>
          <div className="flex items-center gap-2 rounded-xl bg-primary-50 px-3 py-2.5 text-xs text-slate-500">
            <Info className="size-4 shrink-0 text-primary-500" />
            저장하면 변경한 부서 정보가 목록에 바로 반영됩니다.
          </div>
        </div>
      </Modal>

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
