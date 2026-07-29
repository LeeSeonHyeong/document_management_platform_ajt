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
import { ACCOUNT_STATUS, ROLES } from '@/shared/constants/enums'
import { qk } from '@/shared/api/queryKeys'
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

  const mutation = useMutation({
    mutationFn: () => updateUser(userId, form),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: qk.users.all })
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
            <Select
              label="사용자 역할"
              value={form.role}
              onChange={(event) => setForm({ ...form, role: event.target.value })}
              options={[{ value: ROLES.ADMIN, label: '관리자' }, { value: ROLES.EMPLOYEE, label: '사원' }]}
            />
            <Input label="이메일 (로그인 ID)" value={employee?.email ?? ''} disabled className="sm:col-span-2" />
            <div className="sm:col-span-2">
              <p className="mb-2 text-sm font-medium text-slate-700">계정 상태</p>
              <div className="inline-flex rounded-lg bg-slate-100 p-1">
                {[ACCOUNT_STATUS.ACTIVE, ACCOUNT_STATUS.INACTIVE].map((status) => (
                  <button
                    key={status}
                    type="button"
                    onClick={() => setForm({ ...form, accountStatus: status })}
                    className={`rounded-md px-5 py-2 text-sm font-medium ${form.accountStatus === status ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-400'}`}
                  >
                    {status === ACCOUNT_STATUS.ACTIVE ? '활성' : '비활성'}
                  </button>
                ))}
              </div>
              <p className="mt-2 text-xs text-slate-400">비활성 시 로그인이 즉시 차단됩니다.</p>
            </div>
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
