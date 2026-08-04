import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import Button from '@/components/ui/Button'
import Card from '@/components/ui/Card'
import Input from '@/components/ui/Input'
import Select from '@/components/ui/Select'
import Spinner from '@/components/ui/Spinner'
import { useToast } from '@/components/ui'
import { ACCOUNT_STATUS, ACCOUNT_STATUS_LABELS, ROLE_LABELS, ROLES } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
import { useAuth } from '@/hooks/useAuth'
import { updateDepartment } from '@/features/department/api'
import { fetchDepartments, fetchUser, updateUser } from '../api'

function formatDate(value) {
  return value
    ? new Intl.DateTimeFormat('ko-KR', {
        dateStyle: 'medium',
        timeStyle: 'short',
      }).format(new Date(value))
    : '-'
}

export default function EmployeeEditPage() {
  const { userId } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()
  const { user, isSuperAdmin } = useAuth()
  // 본인 계정은 스스로 사원으로 강등하거나 비활성화할 수 없다(백엔드 409). 역할·상태 컨트롤을 잠근다.
  const isSelf = user?.userId === userId
  const userQuery = useQuery({ queryKey: qk.users.detail(userId), queryFn: () => fetchUser(userId) })
  const departmentsQuery = useQuery({ queryKey: qk.departments.list, queryFn: fetchDepartments })
  const [form, setForm] = useState({ name: '', departmentId: '', role: ROLES.EMPLOYEE, accountStatus: ACCOUNT_STATUS.ACTIVE })

  useEffect(() => {
    if (!userQuery.data) return
    // 화면 입력값을 백엔드 JSON 필드와 맞춥니다.
    // department는 객체이므로 수정 요청에는 department.departmentId만 보냅니다.
    setForm({
      name: userQuery.data.name,
      departmentId: userQuery.data.department?.departmentId ?? '',
      role: userQuery.data.role,
      accountStatus: userQuery.data.accountStatus,
    })
  }, [userQuery.data])

  // 부서관리자가 URL로 직접 다른 관리자 수정 화면에 진입하면 저장 시 403이 나므로, 진입 자체를 막고 상세로 돌려보낸다.
  // (일반 흐름에서는 상세 화면에서 수정 버튼이 이미 숨겨져 있어 여기까지 오지 않는다.)
  useEffect(() => {
    if (!userQuery.data) return
    const target = userQuery.data
    const canEdit = isSuperAdmin || target.role === ROLES.EMPLOYEE || target.userId === user?.userId
    if (!canEdit) {
      toast.error('이 계정을 수정할 권한이 없습니다.')
      navigate(`/admin/users/${userId}`, { replace: true })
    }
  }, [userQuery.data, isSuperAdmin, user?.userId, toast, navigate, userId])

  const mutation = useMutation({
    mutationFn: async () => {
      const original = userQuery.data
      const departments = departmentsQuery.data ?? []
      const departmentChanged = original.department?.departmentId !== form.departmentId
      const originalManagedDepartment = departments.find(
        (department) => department.manager?.userId === original.userId,
      )
      const targetDepartment = departments.find(
        (department) => department.departmentId === form.departmentId,
      )
      const displacedManagerId = form.role === ROLES.ADMIN &&
        targetDepartment?.manager?.userId !== original.userId
        ? targetDepartment?.manager?.userId ?? null
        : null
      let employeeChanged = false
      let displacedManagerDemoted = false

      try {
        // 대상 부서에 기존 관리자가 있다면 가장 먼저 강등한다.
        // 기존 관리자에게 미처리 문의가 있으면 이후 역할 변경과 부서 이동을 시작하지 않는다.
        if (displacedManagerId) {
          await updateUser(displacedManagerId, { role: ROLES.EMPLOYEE })
          displacedManagerDemoted = true
        }

        // 관리자가 다른 부서로 이동할 때는 먼저 사원으로 강등한다.
        // 미처리 문의가 있으면 이 요청이 거절되어 이후 부서 이동이 실행되지 않는다.
        if (original.role === ROLES.ADMIN && departmentChanged) {
          await updateUser(userId, { role: ROLES.EMPLOYEE })
          employeeChanged = true
        }

        if (departmentChanged) {
          await updateUser(userId, { departmentId: form.departmentId })
          employeeChanged = true
        }

        // 이동을 먼저 마친 뒤 최종 역할·이름·계정 상태를 반영한다.
        await updateUser(userId, form)
        employeeChanged = true

        if (form.role === ROLES.ADMIN) {
          await updateDepartment(form.departmentId, { managerId: userId })
        }
      } catch (error) {
        // 순차 요청 중 실패하면 직원과 기존 부서 관리자 상태를 가능한 범위에서 복구한다.
        try {
          if (employeeChanged) {
            await updateUser(userId, {
              name: original.name,
              departmentId: original.department?.departmentId,
              role: original.role,
              accountStatus: original.accountStatus,
            })
            if (originalManagedDepartment) {
              await updateDepartment(originalManagedDepartment.departmentId, {
                managerId: original.userId,
              })
            }
          }
          if (displacedManagerId && displacedManagerDemoted) {
            await updateUser(displacedManagerId, { role: ROLES.ADMIN })
            await updateDepartment(form.departmentId, {
              managerId: displacedManagerId,
            })
          }
        } catch {
          // 복구 실패보다 최초 저장 실패 원인을 우선 안내한다.
        }
        throw error
      }
    },
    onSuccess: async () => {
      // 역할 변경으로 부서 관리자가 바뀔 수 있으므로 부서 목록 캐시도 함께 무효화한다
      // (그러지 않으면 부서 관리 화면이 옛 관리자를 계속 보여준다).
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: qk.users.all }),
        queryClient.invalidateQueries({ queryKey: qk.departments.all }),
      ])
      toast.success('직원 정보가 저장되었습니다.')
      navigate(`/admin/users/${userId}`)
    },
    onError: (error) => toast.error(error.message ?? '직원 정보를 저장하지 못했습니다.'),
  })

  if (userQuery.isLoading) return <div className="flex justify-center py-24"><Spinner /></div>
  const employee = userQuery.data

  return (
    <div className="space-y-5">
      <Link to={`/admin/users/${userId}`} className="inline-flex items-center gap-1 text-sm font-medium text-slate-500">
        <ChevronLeft className="size-4" /> 직원 상세
      </Link>
      <div className="grid gap-5 lg:grid-cols-[2fr_1fr]">
        <Card className="p-6">
          <div className="flex items-center justify-between border-b border-slate-100 pb-4">
            <h2 className="text-lg font-bold">기본 정보 수정</h2>
            <span className="text-xs text-slate-400">* 필수 항목</span>
          </div>
          <form className="mt-5 grid gap-4 sm:grid-cols-2" onSubmit={(event) => { event.preventDefault(); mutation.mutate() }}>
            <Input label="사용자명" required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
            <Input label="사번" value={employee?.employeeNo ?? ''} disabled />
            <Select
              label="부서"
              required
              value={form.departmentId}
              onChange={(event) => setForm({ ...form, departmentId: event.target.value })}
              options={(departmentsQuery.data ?? []).map((department) => ({ value: department.departmentId, label: department.name }))}
            />
            {/* 역할·계정 상태는 최고관리자만 변경할 수 있다. 부서관리자에게는 선택 UI 대신 현재 값을 읽기 전용으로 보여준다(S15P11B106-104). */}
            {isSuperAdmin ? (
              <Select
                label="사용자 역할"
                value={form.role}
                onChange={(event) => setForm({ ...form, role: event.target.value })}
                options={[{ value: ROLES.ADMIN, label: '관리자' }, { value: ROLES.EMPLOYEE, label: '사원' }]}
                disabled={isSelf}
                hint={isSelf ? '본인 계정의 역할은 변경할 수 없습니다.' : undefined}
              />
            ) : (
              <Input label="사용자 역할" value={ROLE_LABELS[form.role] ?? form.role} disabled />
            )}
            <Input label="이메일 (로그인 ID)" value={employee?.email ?? ''} disabled className="sm:col-span-2" />
            {isSuperAdmin ? (
              <div className="sm:col-span-2">
                <p className="mb-2 text-sm font-medium text-slate-700">계정 상태</p>
                <div className="inline-flex rounded-lg bg-slate-100 p-1">
                  {[ACCOUNT_STATUS.ACTIVE, ACCOUNT_STATUS.INACTIVE].map((status) => (
                    <button
                      key={status}
                      type="button"
                      disabled={isSelf}
                      onClick={() => setForm({ ...form, accountStatus: status })}
                      className={`rounded-md px-5 py-2 text-sm font-medium ${form.accountStatus === status ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-400'} ${isSelf ? 'cursor-not-allowed opacity-60' : ''}`}
                    >
                      {status === ACCOUNT_STATUS.ACTIVE ? '활성' : '비활성'}
                    </button>
                  ))}
                </div>
                <p className="mt-2 text-xs text-slate-400">
                  {isSelf ? '본인 계정은 비활성화할 수 없습니다.' : '비활성 시 로그인이 즉시 차단됩니다.'}
                </p>
              </div>
            ) : (
              <Input
                label="계정 상태"
                value={ACCOUNT_STATUS_LABELS[form.accountStatus] ?? form.accountStatus}
                disabled
                className="sm:col-span-2"
              />
            )}
            <div className="flex justify-end gap-2 sm:col-span-2">
              <Button variant="outline" onClick={() => navigate(`/admin/users/${userId}`)}>취소</Button>
              <Button type="submit" loading={mutation.isPending}>변경사항 저장</Button>
            </div>
          </form>
        </Card>
        <Card className="h-fit p-6">
          <h2 className="border-b border-slate-100 pb-4 text-lg font-bold">계정 정보</h2>
          <dl className="mt-4 space-y-4">
            <div>
              <dt className="text-xs text-slate-400">로그인 ID</dt>
              <dd className="mt-1 text-sm font-semibold">{employee?.email}</dd>
            </div>
            <div>
              <dt className="text-xs text-slate-400">생성일시</dt>
              <dd className="mt-1 text-sm font-semibold">{formatDate(employee?.createdAt)}</dd>
            </div>
            <div>
              <dt className="text-xs text-slate-400">수정일시</dt>
              <dd className="mt-1 text-sm font-semibold">{formatDate(employee?.updatedAt)}</dd>
            </div>
          </dl>
          <p className="mt-5 rounded-lg bg-slate-100 px-4 py-3 text-xs text-slate-500">저장 시 수정일시가 자동으로 갱신됩니다.</p>
        </Card>
      </div>
    </div>
  )
}
