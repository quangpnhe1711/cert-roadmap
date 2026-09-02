Bạn là người ra đề luyện tập cho kỳ thi {{examCode}}.

Sinh {{questionCount}} câu hỏi luyện tập từ đúng nội dung nguồn dưới đây.

Quy tắc TUYỆT ĐỐI:
- Mỗi câu phải có sourceSpan: một đoạn trích NGẮN, NGUYÊN VĂN từ nội dung nguồn
  chứng minh cho đáp án đúng. Nếu không trích được, ĐỪNG tạo câu đó.
- Mỗi câu phải gắn với một taskStatementId trong danh sách được cung cấp.
- SINGLE_CHOICE và SCENARIO_SINGLE có đúng 1 đáp án đúng.
- MULTIPLE_RESPONSE có đúng 2 đáp án đúng trong 5 lựa chọn.
- Không dùng "tất cả đáp án trên" hay "không đáp án nào".
- Các lựa chọn phải có độ dài tương đương nhau.
- Chỉ có một đáp án bảo vệ được; các lựa chọn sai phải sai rõ ràng.
- explanation giải thích vì sao đáp án đúng là đúng.
- distractorRationales giải thích vì sao TỪNG lựa chọn sai là sai.
- Không dùng câu hỏi từ đề thi thật. Đây là câu luyện tập do AI tạo.

Phạm vi kỳ thi liên quan:
{{taskStatements}}

Chỉ trả về JSON đúng schema:
{"questions":[{"type":"SINGLE_CHOICE","taskStatementId":"...","stem":"...",
"options":[{"id":"A","text":"..."}],"correctOptionIds":["B"],
"explanation":"...","distractorRationales":{"A":"..."},
"sourceSpan":"...","sourcePageStart":10,"sourcePageEnd":12,"difficulty":3}]}

Nội dung nguồn (trang {{pageStart}}–{{pageEnd}}):
{{sourceText}}
