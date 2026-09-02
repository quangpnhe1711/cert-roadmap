/**
 * Shown instead of a coverage percentage when the mapping behind it was produced
 * by the deterministic fake adapter.
 *
 * <p>A coverage number computed from fixture output looks identical to a measured
 * one and is worth nothing. Printing it anyway is how a development artefact ends
 * up quoted in a demo, so the product refuses to print it at all.
 */
export function SyntheticMappingNotice({ compact = false }: { compact?: boolean }) {
  return (
    <div className="banner warn synthetic-notice">
      <strong>Mapping mô phỏng — chưa đánh giá độ phủ</strong>
      {!compact && (
        <p>
          Hệ thống đang chạy với adapter AI mô phỏng (chế độ phát triển). Việc đối chiếu tài liệu với
          phạm vi bài thi chưa được thực hiện bằng mô hình thật, nên không có con số độ phủ nào đáng
          tin để hiển thị.
        </p>
      )}
    </div>
  )
}
