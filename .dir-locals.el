(
 (nil . ((eval . (progn (local-set-key (kbd "C-c C-r")
										 (lambda () (interactive)
										   (cider-interactive-eval
											"(main-default)"
											nil nil (cider--nrepl-pr-request-map))))) ))))
